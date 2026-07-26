# ReleasePilot

ReleasePilot is an internal platform that moves an application version through
an ordered pipeline of deployment environments (`dev → staging → production`).
It is modeled with **DDD** (a `Promotion` aggregate that owns all business
rules), **CQRS** (separate write and read paths), and **domain events** (every
state transition is published and consumed asynchronously).

The API is intentionally thin: it validates input and translates HTTP into
commands/queries. All business logic — legality of a transition, who can
approve what, immutability after a terminal state — lives inside the
aggregate. Rule violations are domain errors mapped to 4xx responses, never
uncaught exceptions.

See [SPECS.md](SPECS.md) for the full technical specification.

## Tech stack

- Java 21
- Spring Boot 4 (Spring Web)
- Maven (via the included Maven Wrapper)

## Prerequisites

- Java 21 (JDK)

No local Maven installation is required — this project uses the Maven
Wrapper (`mvnw`).

## Running the application

From the project root:

```bash
./mvnw spring-boot:run
```

On Windows:

```bash
mvnw.cmd spring-boot:run
```

The application starts on [http://localhost:8080](http://localhost:8080).

## Building

```bash
./mvnw clean package
```

Produces an executable jar in `target/`, which can be run with:

```bash
java -jar target/releasepilot-0.0.1-SNAPSHOT.jar
```

## Running tests

```bash
./mvnw test
```

This runs the unit test suite only (aggregate, command-service, query-service, and
`@WebMvcTest` controller/error-mapping tests) — it needs no database, Docker, or other
infrastructure, and finishes in a few seconds. Persistence and full-context integration tests
are excluded by naming convention (`*Test` vs `*IT`, see below).

### Integration tests

Every `*IT` class (`src/test/java/.../infrastructure/persistence/`,
`.../infrastructure/persistence/readmodel/`, `.../infrastructure/queue/`, and
`ReleasePilotApplicationIT`) runs against a real, disposable PostgreSQL instance (and, for the
queue tests, RabbitMQ too) started automatically by [Testcontainers](https://testcontainers.com/)
— no manual setup is required, but **Docker must be installed and running** (the container
images are pulled and started/stopped per test run).

They're named `*IT` rather than `*Test` specifically so the default `./mvnw test` (Surefire)
skips them; only Maven's `verify` lifecycle phase (bound to the Failsafe plugin) runs them:

```bash
./mvnw verify
```

To run just the integration tests without the rest of the `verify` lifecycle:

```bash
./mvnw failsafe:integration-test
```

What they prove: `JdbcPromotionRepositoryIT` shows the write-side adapter's SQL round-trips every
field of a `Promotion` and the two invariant-scoped queries filter correctly, replaying the exact
call sequence `PromotionCommandService` uses. `JdbcPromotionReadModelRepositoryIT` seeds the
read-model tables directly (standing in for the projector consumer described below) and asserts
the three query shapes (§7) — the promotion-detail history ordering, the "always three
environments" defaulting, and the paged/ordered promotion history — are correct at the SQL level.
`ReleasePilotApplicationIT` boots the entire Spring context against a real database, proving every
`@Repository`/`@Service`/`@Component`/`@RestController` bean introduced for the HTTP layer
actually wires together.

## CI/CD

Two GitHub Actions workflows live in `.github/workflows/`:

- **`ci.yml`** — runs on every push, on three independent jobs: `build`
  (`./mvnw clean compile`), `unit-tests` (`./mvnw test`) and
  `integration-tests` (`./mvnw verify`, which needs the Docker daemon that
  GitHub-hosted runners already provide, for Testcontainers). Any job failure
  fails the pipeline.
- **`release.yml`** — runs on every push to `main` (i.e. once a branch is
  merged). It re-runs `ci.yml` in full via `workflow_call`, and only if that
  passes, builds the image from the [Dockerfile](Dockerfile) and pushes it to
  the GitHub Container Registry as `ghcr.io/<owner>/<repo>:latest` and
  `ghcr.io/<owner>/<repo>:sha-<commit>`.

## The `Promotion` aggregate lifecycle

A `Promotion` moves one application version one step through the pipeline
(`dev → staging → production`). Every transition is a guarded method on the
aggregate itself — illegal transitions raise a domain error rather than
silently mutating state or throwing an uncaught exception.

```
Requested ──approve──▶ Approved ──start──▶ InProgress ──complete──▶ Completed
    │                      │                    │
    │                      │                    └──rollback──▶ RolledBack
    │                      │
    └──────cancel──────────┴──────cancel────────▶ Cancelled
```

| State | Reached via | Guards enforced on entry |
|---|---|---|
| `Requested` | `Promotion.request(...)` | target environment is the immediate next step after the version's last completed environment (no skipping); no other non-terminal promotion already targets the same `(application, environment)` pair |
| `Approved` | `approve(actor)` | promotion is not terminal and is currently `Requested`; `actor` holds the `approver` role |
| `InProgress` | `startDeployment(actor)` | promotion is not terminal and is currently `Approved` |
| `Completed` *(terminal)* | `complete(actor)` | promotion is not terminal and is currently `InProgress` |
| `RolledBack` *(terminal)* | `rollback(actor)` | promotion is not terminal and is currently `InProgress` |
| `Cancelled` *(terminal)* | `cancel(actor)` | promotion is not terminal and is currently `Requested` or `Approved` |

Once a promotion reaches a terminal state (`Completed`, `RolledBack`,
`Cancelled`) every subsequent command is rejected with
`PromotionAlreadyTerminalError` — no field on a terminal promotion can change.
Any other out-of-order command (e.g. approving a promotion that is already
`Approved`) is rejected with `InvalidTransitionError`. Both are domain errors
(`domain/promotion/errors/`), never uncaught exceptions.

Checking the "no skipping" and "no duplicate in-flight promotion" invariants
requires knowledge of sibling `Promotion` instances for the same application,
so the application layer (`PromotionCommandService`, the implementation of
the `PromotionCommandPort` input port) fetches that sibling data and passes
it into `Promotion.request(...)` — but the aggregate itself still makes the
decision and throws the domain error if a rule is violated.

`DeploymentPort`, `IssueTrackerPort`, and `NotificationPort`
(`domain/ports/`) are the domain's output ports: capabilities it requires of
the outside world, defined here as plain interfaces with no implementation
yet — infrastructure adapters (in-memory stubs, later real clients) are a
separate concern layered on top.

`PromotionRepositoryPort` (`domain/ports/`), the write-side persistence output
port, is implemented by `JdbcPromotionRepository`
(`infrastructure/persistence/`) — plain `JdbcTemplate` mapping rows to/from
`Promotion` explicitly, deliberately not Spring Data JPA/JDBC, so the
aggregate's own constructors stay in control of its invariants rather than a
framework reconstructing it via reflection (see SPECS.md §11). The schema
lives in `db/migration/V1__create_promotions_table.sql`, applied by Flyway.
It is registered as a `@Repository` bean now that the HTTP layer is connected
to the command handlers (see "HTTP API" below) and is also still exercised
directly by `JdbcPromotionRepositoryIT` (see "Integration tests" above). Note
that `src/test/resources/application.yml` excludes `DataSourceAutoConfiguration`
for plain `@SpringBootTest`/`@WebMvcTest` unit tests, since those never touch a
database; `ReleasePilotApplicationIT` overrides that exclusion to boot the
full context against a real Testcontainers Postgres instance instead.

## Event publishing (transactional outbox)

Every guarded transition on `Promotion` (`request`, `approve`, `startDeployment`,
`complete`, `rollback`, `cancel`) records the domain event SPECS.md §5 assigns
it — `PromotionRequested`, `PromotionApproved`, `DeploymentStarted`,
`PromotionCompleted`, `PromotionRolledBack`, `PromotionCancelled` — on the
aggregate itself, in a pending list that never gets persisted. Nothing about
recording an event depends on infrastructure: the aggregate stays exactly as
easy to unit-test as before (`PromotionTest`'s "Domain events" cases assert
against `pullDomainEvents()` with no ports, no database, no broker).

`PromotionCommandService`, the command handler for all six commands, calls
`Promotion.pullDomainEvents()` right after `PromotionRepositoryPort.save(...)`
in every single handler method, and publishes what it gets back through
`EventPublisherPort` (`domain/ports/`) — the port the domain defines for "hand
this event off for reliable delivery." (`StartDeployment` is the one
exception worth calling out: `DeploymentStarted`'s `deploymentRef` payload
field isn't known until *after* the transition, once `DeploymentPort.trigger`
returns, so the handler enriches the pulled event with
`DomainEvent.withPayload(...)` before publishing it.)

`EventPublisherPort` is implemented by `OutboxEventPublisher`
(`infrastructure/persistence/`): a plain `JdbcTemplate` insert into the
`outbox_events` table (`db/migration/V2__create_outbox_events_table.sql`),
using the same connection the aggregate's own save uses, so the two writes
commit or roll back together — the transactional-outbox pattern from
SPECS.md §5.1. The API can therefore respond as soon as that transaction
commits; nothing about delivering the event to the broker blocks the request.

Delivery to the broker is a second, decoupled step: `OutboxRelay`
(`infrastructure/queue/`) polls `outbox_events` for rows with
`published_at IS NULL`, publishes each to the `promotion.events` fanout
RabbitMQ exchange via Spring AMQP's `RabbitTemplate`, and marks the row sent.
Using a fanout exchange means the relay never needs to know which or how many
consumers exist — each future consumer (audit log, read-model projector,
notifications, ...) declares and binds its own queue. `OutboxEventPublisher`
is now registered as a `@Component` bean (the command handlers need it), but
`OutboxRelay` itself is intentionally not yet scheduled/wired into the Spring
context — actually polling and delivering to a live broker is part of the
async-processing/consumers work (SPECS §8), not the HTTP command/query layer
this branch adds. Both are still exercised directly by `OutboxEventPublisherIT`
and `OutboxRelayIT` (Testcontainers Postgres + RabbitMQ — the latter proves an
event genuinely survives the outbox → queue hop and isn't redelivered on a
second relay pass).

## HTTP API (commands and queries)

The nine endpoints from SPECS.md §10 are wired now:

| Method | Path | Command/Query |
|---|---|---|
| POST | `/promotions` | `RequestPromotion` |
| POST | `/promotions/:id/approve` | `ApprovePromotion` |
| POST | `/promotions/:id/start` | `StartDeployment` |
| POST | `/promotions/:id/complete` | `CompletePromotion` |
| POST | `/promotions/:id/rollback` | `RollbackPromotion` (optional `reason`) |
| POST | `/promotions/:id/cancel` | `CancelPromotion` (optional `reason`) |
| GET | `/promotions/:id` | promotion detail |
| GET | `/applications/:id/status` | per-environment status |
| GET | `/applications/:id/promotions` | paged promotion history (`?page=&pageSize=`) |

`PromotionCommandController` and `PromotionQueryController` (`api/controllers/`) are thin: they
parse the request, translate it into the arguments `PromotionCommandPort`/`PromotionQueryPort`
need (`api/dto/`), and delegate — no business logic lives in either class. Every documented
business-rule violation, plus "not found" and malformed-input cases, is translated to a 4xx by
`ErrorMapping` (`api/ErrorMapping.java`), a `@RestControllerAdvice` that switches exhaustively over
the sealed `DomainError` hierarchy (SPECS §10/§11) — adding a new `DomainError` subtype without
extending that switch fails the build, not a request at runtime.

The actor concept (SPECS §15) is a trusted `{ userId, role }` object on every write request body
(`ActorRequest`), translated to the domain's `Actor` value object at the API boundary.

On the write side, `PromotionCommandPort` gained a reason-carrying overload for
`rollbackPromotion`/`cancelPromotion` — SPECS §4/§5 lists `reason?` as part of both commands'
input and their events' payload, so `Promotion.rollback`/`cancel` now take an optional `reason`
too (an additive overload; existing call sites without a reason are unaffected). Every command
handler method is `@Transactional`, so the aggregate's persisted state and its outbox row commit
or roll back together, per SPECS §5.1.

On the read side, `PromotionQueryPort`/`PromotionQueryService` (`application/queries/`) are thin
pass-throughs to `PromotionReadModelPort`, backed by `JdbcPromotionReadModelRepository`
(`infrastructure/persistence/readmodel/`) and the `promotion_detail`, `promotion_detail_history`,
`application_environment_status`, and `promotion_history` tables
(`db/migration/V3__create_read_model_tables.sql`) — separate from, and never joined against, the
write-side `promotions` table, per CQRS. **These tables are not yet populated by anything**: the
read-model projector consumer that would fill them by consuming events off the queue is SPECS §8
work, not yet built. Until then, `GET` requests against a fresh database correctly return 404 /
empty results — a data-population gap, not a bug in the query path, which is why
`JdbcPromotionReadModelRepositoryIT` seeds rows directly (standing in for the future projector) to
prove the queries themselves are correct.

All three output ports from SPECS §6 now have in-memory stubs registered as `@Component` beans in
`infrastructure/adapters/inmemory/`: `InMemoryDeploymentAdapter` (called synchronously by
`StartDeployment`, needed for the app context to boot), `InMemoryIssueTrackerAdapter` (seeded/keyed
by `(applicationId, version)`, called by the release-notes agent consumer below), and
`InMemoryNotificationAdapter`. The latter still just has its stub bean and unit test for now — the
Notification consumer that would call `NotificationPort.notify(...)` on terminal-state events
(SPECS §8.3) is still to be built.

## AI release-notes agent (optional, SPECS §9)

`ReleaseNotesAgentConsumer` (`consumers/`) is the one consumer triggered by a single event type:
every event other than `PromotionApproved` delivered off the `promotion.events` fanout exchange is a
no-op. On `PromotionApproved` it drives a genuine tool-calling loop rather than a single completion —
repeatedly asking `ReleaseNotesLlmPort` (`domain/ports/`) for the next `AgentDecision` and executing
whichever tool it names, appending the result to an `AgentContext` transcript and handing the updated
context back for the next decision, until the LLM returns `Done` instead of another tool call:

1. `get_linked_work_items` → `IssueTrackerPort`.
2. If that first fetch came back empty, the mocked model decides to re-fetch once before giving up —
   the "needs another tool call" branch SPECS §9 calls out — rather than always re-fetching or never
   retrying.
3. `get_promotion_history` → `PromotionReadModelPort`, for "since last release" framing.
4. `save_release_notes` — persists the draft to the `release_notes` table
   (`db/migration/V5__create_release_notes_table.sql`), upserted on `promotion_id` (a promotion is
   approved at most once) so redelivery under the outbox's at-least-once guarantee never conflicts.

`ReleaseNotesLlmPort` is implemented by `MockedReleaseNotesLlmAdapter`
(`infrastructure/adapters/inmemory/`): genuinely mocked (deterministic canned decisions keyed off the
tool results seen so far), but the consumer round-trips through it exactly as it would a real
chat-completions API with tool-calling. Like the other consumers, `ReleaseNotesAgentConsumer` itself
is plain Java with no Spring dependency (SPECS §14.3); `ReleaseNotesAgentConfig`/
`ReleaseNotesAgentListener` (`infrastructure/queue/`) wire it up and bind its queue only under the
`release-notes-agent-consumer` Spring profile, mirroring the audit-log and read-model-projector
consumers' deploy-as-its-own-process shape.

## Project structure

```
src/main/java/com/releasepilot/
  domain/          # Promotion aggregate, value objects, invariants, ports
  application/      # Command and query handlers, read-model port, query DTOs
  infrastructure/    # Persistence (write + read model), in-memory adapters, message queue
  consumers/         # Async event consumers (audit log, read-model projections, release-notes agent); notification consumer not yet built
  api/                # Thin REST controllers, request/response DTOs, error mapping
src/main/resources/
  db/migration/       # Flyway SQL migrations
  application.yml
```
