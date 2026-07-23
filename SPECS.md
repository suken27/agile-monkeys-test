# ReleasePilot — Technical Specification

## 1. Overview

ReleasePilot is an internal platform that moves an application version through an
ordered pipeline of deployment environments (`dev → staging → production`). The
system is modeled with **DDD** (a `Promotion` aggregate that owns all business
rules), **CQRS** (separate write and read paths), and **domain events** (every
state transition is published and consumed asynchronously).

The API is intentionally thin: it does input validation and translates
HTTP ↔ commands/queries. All business logic — legality of a transition, who can
approve what, immutability after a terminal state — lives inside the aggregate.
Rule violations are **domain errors** (mapped to 4xx), never uncaught
exceptions (never 5xx).

### 1.1 Goals

- Model `Promotion` as a self-guarding aggregate — invariants cannot be
  bypassed by any command handler, repository, or API layer.
- Full command → event → async-consumer pipeline, including a persisted audit
  log built entirely from consumed events (not written inline by command
  handlers).
- Read models shaped for their consumers, decoupled from the aggregate's
  internal representation.
- External systems (deployment trigger, issue tracker, notifications) are
  ports with in-memory stub adapters — no outbound HTTP in this exercise.
- A single `docker-compose.yml` that boots the database and the message
  queue with no other manual setup.

### 1.2 Non-goals

- Real integrations with a CI/CD system, issue tracker, or notification
  provider — stubs only.
- Auth/authz beyond a minimal actor concept (`userId` + `role`) needed to
  enforce "only an approver may approve."
- Multi-tenancy, horizontal scaling, or production-grade observability.
- A UI. This is an API-only deliverable.

---

## 2. Architecture Style

Layered / hexagonal, with CQRS inside the write side:

```
┌─────────────────────────── HTTP (thin) ───────────────────────────┐
│  Controllers: parse request → build Command/Query → call bus      │
└───────────────┬─────────────────────────────────────┬─────────────┘
                 │                                     │
         ┌───────▼────────┐                    ┌───────▼────────┐
         │  Command Bus     │                    │   Query Bus     │
         │  (write side)     │                    │  (read side)    │
         └───────┬────────┘                    └───────┬────────┘
                 │ one handler per command             │ one handler per query
         ┌───────▼─────────────────┐            ┌───────▼─────────────────┐
         │ Application layer         │            │ Read model repositories │
         │  - loads aggregate         │            │  - query denormalized   │
         │  - calls aggregate method  │            │    projections directly │
         │  - persists + emits events │            │  (never touch aggregate)│
         └───────┬─────────────────┘            └──────────────────────────┘
                 │
         ┌───────▼─────────────────┐
         │ Domain layer (core)       │
         │  Promotion aggregate       │
         │  Value objects, invariants │
         │  Ports (interfaces only)   │
         └───────┬─────────────────┘
                 │ publishes DomainEvent
         ┌───────▼─────────────────┐
         │ Outbox → Message Queue    │
         └───────┬─────────────────┘
                 │ consumed asynchronously, own process/module
      ┌──────────┼──────────────┬─────────────────────┐
      ▼          ▼              ▼                     ▼
 Audit Log   Read-model      Notification         [Optional]
 consumer    projector       consumer             Release-notes
 (persists   (updates        (stub port,          agent consumer
 every event)  query tables)  terminal states)     (on Approved)
```

Key design decision: **ports live in the domain layer** (e.g.
`domain/ports/DeploymentPort.java`), because they represent capabilities the
domain *requires* of the outside world, expressed in domain language
(`triggerDeployment(promotionId, environment)`), not infrastructure language
(`POST /deploy`). Adapters (the in-memory stubs, and any future real client)
live in `infrastructure/adapters/` and implement those interfaces. This keeps
the dependency arrow pointing inward — the domain never imports
infrastructure, and in particular never imports a Spring annotation — and
makes it trivial to swap a stub for a real HTTP client later without touching
a single line of domain or application code. This is the question the
exercise flags ("you'll be asked why") — the answer is dependency inversion:
the domain defines the contract it needs, infrastructure fulfills it. Spring
wires the concrete adapter to the port interface via `@Bean`/`@Component` and
constructor injection, but the domain module itself has zero Spring
dependency — it is plain Java, which is also what keeps the aggregate unit
tests in §14 free of any Spring context startup.

---

## 3. Domain Model

### 3.1 Ubiquitous language

| Term | Meaning |
|---|---|
| Application | A deployable unit identified by `applicationId`. |
| Version | An immutable build/artifact identifier for an application (e.g. semver or commit SHA). |
| Environment | A stage in the pipeline. Fixed, ordered: `dev`, `staging`, `production`. |
| Promotion | A request/process to move one application version one step forward in the pipeline. The aggregate root. |
| Approver | An actor with the `approver` role, distinct from the requester. |
| Terminal state | `Completed`, `RolledBack`, or `Cancelled` — no further transitions possible. |

### 3.2 Promotion states & transitions

```
 Requested ──approve──▶ Approved ──start──▶ InProgress ──complete──▶ Completed
     │                      │                    │
     │                      │                    └──rollback──▶ RolledBack
     │                      │
     └──────cancel──────────┴──────cancel────────▶ Cancelled
```

| From state | Command | Guard | To state | Event |
|---|---|---|---|---|
| — | `RequestPromotion` | target env is the *immediate next* pipeline step after the version's current environment; no other non-terminal promotion exists for this `(applicationId, targetEnvironment)` | `Requested` | `PromotionRequested` |
| `Requested` | `ApprovePromotion` | actor has `approver` role; actor ≠ requester (recommended, see §3.4) | `Approved` | `PromotionApproved` |
| `Approved` | `StartDeployment` | — | `InProgress` | `DeploymentStarted` |
| `InProgress` | `CompletePromotion` | — | `Completed` (terminal) | `PromotionCompleted` |
| `InProgress` | `RollbackPromotion` | — | `RolledBack` (terminal) | `PromotionRolledBack` |
| `Requested` \| `Approved` | `CancelPromotion` | promotion is not already terminal | `Cancelled` (terminal) | `PromotionCancelled` |

Any command issued against a promotion already in a terminal state, or any
command whose guard fails, raises a **domain error** (e.g.
`PromotionAlreadyTerminalError`, `InvalidTransitionError`,
`UnauthorizedApproverError`, `EnvironmentSkippedError`,
`DuplicatePromotionInProgressError`) — surfaced by the API as `409 Conflict`
or `422 Unprocessable Entity`, never `500`.

### 3.3 Invariants (enforced inside the aggregate, not the handler)

1. **Ordered pipeline, no skipping** — a promotion's `targetEnvironment` must
   be the environment immediately following the version's last *completed*
   environment. Requesting `production` before `staging` is completed is
   rejected at aggregate construction/method time.
2. **One in-flight promotion per (application, target environment)** — enforced
   by the aggregate refusing to transition into `Requested` while a prior
   non-terminal promotion exists for the same pair. (The uniqueness check
   itself requires a repository lookup before invoking the aggregate — see
   §3.4 — but the *decision* of what counts as "in progress" and the refusal
   itself is domain logic exposed via a method like
   `Promotion.assertNoConflict(existing)`.)
3. **Only an approver may approve** — `ApprovePromotion` requires
   `actor.role === 'approver'`; the aggregate rejects otherwise.
4. **Immutability after terminal state** — once `Completed`, `RolledBack`, or
   `Cancelled`, every command is rejected with a domain error. No field on a
   terminal `Promotion` may change.
5. **Every transition is one step** — `RequestPromotion` cannot jump two
   environments in one command; each hop is its own `Promotion` instance/row
   with its own lifecycle.

### 3.4 Aggregate boundary note

A `Promotion` aggregate instance is scoped to *one* application-version's
*one* hop between two adjacent environments. Invariant #1 (must complete
prior environment) and #2 (no duplicate in-flight promotion for the same
target) both require knowledge of *other* Promotion instances for the same
application. This is handled the standard DDD way: the **application layer**
(command handler) loads the necessary sibling promotions/version status via
the repository and passes them into the aggregate's factory/method (e.g.
`Promotion.request(previousEnvironmentState, existingPromotionsForTarget,
...)`), so the *decision* is still made by the aggregate, but the *data
fetching* — which is cross-aggregate — is the handler's job. This keeps each
`Promotion` aggregate instance small and consistent-boundary-correct (one
transaction per aggregate instance) while still upholding invariants that
logically span multiple instances of the same aggregate type.

### 3.5 Value objects

- `ApplicationId`, `PromotionId` (UUID)
- `Version` (opaque non-empty string)
- `Environment` — enum `dev | staging | production`, with a total order and
  `.next()` / `.previous()`.
- `Actor` — `{ userId, role: 'requester' | 'approver' }`
- `PromotionStatus` — enum matching §3.2 states.

---

## 4. Commands

One handler per command, each living in `application/commands/<Verb>/`:

| Command | Input | Emits |
|---|---|---|
| `RequestPromotion` | `applicationId, version, targetEnvironment, requestedBy` | `PromotionRequested` |
| `ApprovePromotion` | `promotionId, approvedBy` | `PromotionApproved` |
| `StartDeployment` | `promotionId, startedBy` | `DeploymentStarted` |
| `CompletePromotion` | `promotionId, completedBy` | `PromotionCompleted` |
| `RollbackPromotion` | `promotionId, rolledBackBy, reason?` | `PromotionRolledBack` |
| `CancelPromotion` | `promotionId, cancelledBy, reason?` | `PromotionCancelled` |

Handler shape (identical skeleton for all six):

```
handle(command):
  promotion = repository.load(command.promotionId)   // or build context for Request
  promotion.<domainMethod>(command)                   // throws DomainError on violation
  repository.save(promotion)                          // persists new state + appends event to outbox (same transaction)
  return promotion.id
```

`StartDeployment`'s handler additionally calls `DeploymentPort.trigger(...)`
after the state transition succeeds (see §6).

---

## 5. Domain Events

All events are immutable facts, versioned, and carry enough data for
consumers to act without calling back into the write side:

```java
public record DomainEvent(
    UUID eventId,
    String eventType,          // "PromotionRequested" | ...
    Instant occurredAt,
    UUID promotionId,
    UUID applicationId,
    String actingUser,
    Map<String, Object> payload // event-specific fields
) {}
```

| Event | Extra payload |
|---|---|
| `PromotionRequested` | `version, fromEnvironment, targetEnvironment` |
| `PromotionApproved` | `approvedBy` |
| `DeploymentStarted` | `deploymentRef` (from `DeploymentPort`) |
| `PromotionCompleted` | — |
| `PromotionRolledBack` | `reason?` |
| `PromotionCancelled` | `reason?` |

### 5.1 Reliable publication: transactional outbox

To honor "fire and forget" without silently losing events, the command
handler writes the new aggregate state **and** an outbox row in the same DB
transaction. A separate lightweight relay polls the outbox table and
publishes to the queue, marking rows as sent. This guarantees at-least-once
delivery even if the broker is briefly unavailable, while the HTTP response
still returns immediately after the DB transaction commits — the queue
publish and all downstream consumption happen after the response is sent.

---

## 6. Ports (hexagonal boundaries)

Defined as interfaces in `domain/ports/`, implemented as in-memory stubs in
`infrastructure/adapters/in-memory/`:

| Port | Method(s) | Used by | Stub behavior |
|---|---|---|---|
| `DeploymentPort` | `trigger(applicationId, version, environment): DeploymentRef` | `StartDeployment` handler | Returns a fake `deploymentRef`, records the call in memory for test assertions. |
| `IssueTrackerPort` | `getLinkedWorkItems(applicationId, version): WorkItem[]` | Query side (promotion detail) and/or release-notes agent | Returns a canned/seeded list of fake tickets. |
| `NotificationPort` | `notify(event: DomainEvent): void` | Notification consumer, on terminal-state events | Logs/records the notification in memory. |

Why domain-layer interfaces, application/infra-layer implementations: it lets
command handlers and the aggregate depend only on abstractions they define,
satisfies the Dependency Inversion Principle, and means unit tests for the
domain never need a real broker, HTTP mock, or database — just the stub
adapters, which is also exactly what the exercise stub requirement rewards.

---

## 7. Queries & Read Models

Read models are separate tables/projections, populated by a projector
consumer (see §8), never read from the write-side aggregate tables directly.

| Endpoint | Read model | Shape |
|---|---|---|
| `GET /promotions/:id` | `promotion_detail` | `{ id, applicationId, version, fromEnvironment, targetEnvironment, status, requestedBy, approvedBy?, history: [{status, actor, occurredAt}] }` |
| `GET /applications/:id/status` | `application_environment_status` | `{ applicationId, environments: [{ environment, currentVersion, status, lastPromotionId }] }` per pipeline stage |
| `GET /applications/:id/promotions` | `promotion_history` (paged) | `{ items: [{ id, version, fromEnvironment, targetEnvironment, status, requestedAt, completedAt? }], page, pageSize, total }` |

State history embedded in `GET /promotions/:id` is built by the audit/projection
consumer appending a row per consumed event, not recomputed on read.

---

## 8. Async Processing

Every event on the queue is consumed by one or more independent, decoupled
handlers (each could run as its own process in production; in this exercise
they can run as separate modules/workers started by the same
`docker-compose`/app entrypoint, but must not share in-process call stacks
with the command handlers):

1. **Audit Log consumer** — persists every event verbatim to an
   `audit_log` table: `(id, eventType, promotionId, occurredAt, actingUser,
   payload)`. This is the system of record for "what happened when," and is
   never written to by command handlers directly — only by consuming the
   published event, which also proves the queue path actually works end to
   end.
2. **Read-model projector** — updates `promotion_detail`,
   `application_environment_status`, `promotion_history` tables per event.
3. **Notification consumer** — calls `NotificationPort.notify(...)` on
   terminal-state events (`PromotionCompleted`, `PromotionRolledBack`,
   `PromotionCancelled`).
4. **(Optional) Release-notes agent consumer** — triggered on
   `PromotionApproved` (see §9).

The API returns as soon as the command handler's transaction commits
(aggregate state + outbox row written) — it does not wait on any consumer.

---

## 9. [Optional] AI Release-Notes Agent

Triggered by `PromotionApproved`. Implemented as a genuine tool-calling loop,
not a single completion:

```
loop:
  1. Agent receives goal: "Draft release notes for promotion {id}."
  2. Agent calls tool `get_linked_work_items(applicationId, version)`
     → backed by IssueTrackerPort stub.
  3. Agent calls tool `get_promotion_history(applicationId)` (optional,
     for "since last release" framing) → backed by read model.
  4. (Mocked LLM decides, from tool results, whether it has enough context
     or needs another tool call — e.g. re-fetch on empty ticket list.)
  5. Agent calls tool `save_release_notes(promotionId, draftText)` →
     persists to a `release_notes` table.
loop ends when agent emits a final "done" action instead of a tool call.
```

The LLM backend is mocked (deterministic canned responses keyed off tool
input), but the harness genuinely round-trips: prompt → tool call → tool
result appended to context → next model call → ... → final answer. This
demonstrates the agent loop shape without requiring a real LLM API key.

---

## 10. API Surface (thin controllers)

| Method | Path | Maps to |
|---|---|---|
| POST | `/promotions` | `RequestPromotion` |
| POST | `/promotions/:id/approve` | `ApprovePromotion` |
| POST | `/promotions/:id/start` | `StartDeployment` |
| POST | `/promotions/:id/complete` | `CompletePromotion` |
| POST | `/promotions/:id/rollback` | `RollbackPromotion` |
| POST | `/promotions/:id/cancel` | `CancelPromotion` |
| GET | `/promotions/:id` | Query §7 |
| GET | `/applications/:id/status` | Query §7 |
| GET | `/applications/:id/promotions?page=&pageSize=` | Query §7 |

Domain errors map to HTTP status via a small, explicit table (e.g.
`InvalidTransitionError → 409`, `EnvironmentSkippedError → 422`,
`UnauthorizedApproverError → 403`, `NotFoundError → 404`); anything unmapped
and truly unexpected is a `500`, but every *documented business rule*
violation has an explicit domain error class and never falls through to
`500`.

---

## 11. Tech Stack (proposed)

| Concern | Choice | Rationale |
|---|---|---|
| Language/runtime | Java 21 (LTS) | Records for value objects/events, sealed interfaces for exhaustive state/error modeling, pattern matching for the transition/error-mapping tables — strong typing for DDD with no extra language runtime to justify. |
| Application framework | Spring Boot (Spring Web for the API, Spring Context for DI) | Wiring, config, and embedded server for free, but deliberately *not* used to drive the CQRS/DDD shape itself — no Axon Framework, no Spring Data REST, no `@Transactional`-driven magic inside the domain. Controllers are thin `@RestController`s that build a Command/Query and hand it to the bus; that keeps the CQRS/DDD wiring visible and hand-rolled rather than hidden behind framework annotations. |
| Command/Query bus | Hand-rolled in-process dispatcher (plain Java, registered as a Spring bean at startup — not Axon's `CommandGateway`) | Demonstrates understanding of the pattern rather than delegating it to a framework module. |
| Database | PostgreSQL | Relational integrity for the write side + `jsonb` for event/read-model payloads; one engine for both write and read tables keeps `docker-compose.yml` minimal. |
| Persistence access | Spring Data JDBC (or plain `JdbcTemplate`) for the write side; the same for read-model projections | Deliberately not Spring Data JPA — the `Promotion` aggregate should not be a JPA entity with lazy proxies and identity-map surprises leaking into the domain layer; a repository that explicitly maps rows ↔ domain objects keeps the aggregate's invariants (§3.3) in full control of its own state. |
| Message queue | RabbitMQ (via Spring AMQP / `spring-boot-starter-amqp`) | Literally a message queue (matches the brief precisely), simple docker image, simple ack/nack semantics for consumers, and Spring AMQP's `@RabbitListener` maps cleanly onto the consumer modules in §8 without pulling consumer logic into the command-handling call stack. |
| Migrations | Flyway | Explicit, reviewable, versioned SQL schema history; first-class Spring Boot auto-configuration. |
| Build tool | Maven | Ubiquitous, declarative, no build-script DSL to explain in a review — favors reviewability over Gradle's flexibility for an exercise of this size. |
| Tests | JUnit 5 + AssertJ + Mockito; aggregate unit tests with zero Spring context; handler tests against stub ports; Testcontainers (Postgres + RabbitMQ) for the handful of API-level integration tests | Domain rules get fast, Spring-context-free unit tests — the highest-value tests here — while integration tests still exercise the real outbox → queue → consumer path via Testcontainers rather than mocks. |

This is a proposal within the brief's "any backend stack you like" — Java 21
and Spring Boot per instruction; the rest of this document's DDD/CQRS/ports
structure is unchanged and framework-agnostic by design, which is what makes
swapping the stack a low-risk edit rather than a rewrite.

---

## 12. Suggested Project Structure

```
src/main/java/com/releasepilot/
  domain/
    promotion/
      Promotion.java             # aggregate root
      PromotionStatus.java
      Environment.java
      errors/                    # domain error classes (sealed hierarchy)
    ports/
      DeploymentPort.java
      IssueTrackerPort.java
      NotificationPort.java
  application/
    commands/
      requestpromotion/{RequestPromotionCommand.java,RequestPromotionHandler.java}
      approvepromotion/...
      startdeployment/...
      completepromotion/...
      rollbackpromotion/...
      cancelpromotion/...
    queries/
      getpromotiondetail/...
      getapplicationstatus/...
      getapplicationpromotions/...
  infrastructure/
    persistence/
      PromotionRepository.java    # write side (Spring Data JDBC / JdbcTemplate)
      OutboxRepository.java
      readmodel/                  # projector-maintained tables
    adapters/
      inmemory/
        InMemoryDeploymentAdapter.java
        InMemoryIssueTrackerAdapter.java
        InMemoryNotificationAdapter.java
    queue/
      RabbitMqPublisher.java
      OutboxRelay.java
  consumers/
    AuditLogConsumer.java
    ReadModelProjector.java
    NotificationConsumer.java
    ReleaseNotesAgentConsumer.java   # optional
  api/
    controllers/
    ErrorMapping.java
src/main/resources/
  db/migration/                     # Flyway SQL migrations
  application.yml
src/test/java/com/releasepilot/...  # mirrors main, per §14
pom.xml
docker-compose.yml
```

---

## 13. docker-compose.yml (scope)

Services:
- `postgres` — with a seeded volume/init script for schema.
- `rabbitmq` (management image, so the UI is available for demo/debugging).
- Optionally the app + consumers as additional services once containerized,
  but at minimum the brief only requires DB + queue to be one-command-up;
  the app itself can run locally against those two containers during
  development.

---

## 14. Testing Strategy

1. **Aggregate unit tests** (no infra) — every invariant in §3.3 gets a
   direct test: legal transition succeeds, illegal one throws the specific
   domain error.
2. **Command handler tests** — using in-memory repository + stub ports,
   verifying the right event shape is produced and persisted to the outbox.
3. **Consumer tests** — feed a `DomainEvent` in, assert the audit row / read
   model / notification call is correct.
4. **API integration tests** — a handful of end-to-end happy-path +
   rule-violation cases asserting HTTP status codes match §10's error
   mapping.

---

## 15. Open Questions / Assumptions to confirm

- Actor/auth model: assuming a trusted `{ userId, role }` passed per request
  (e.g. a header or body field) rather than building real authentication —
  confirm this is acceptable scope.
- Whether "approver ≠ requester" should be a hard rule or just recommended;
  the brief only says "only an approver may approve," so self-approval by a
  user who holds the approver role is allowed unless told otherwise.
- Whether consumers should run as separate OS processes/containers in this
  exercise or as separate in-app workers — proposing the latter for
  simplicity, structured so extracting them into standalone processes later
  is a deployment change, not a code change.
