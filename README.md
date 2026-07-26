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

This runs the unit test suite only (aggregate + command-service tests) — it needs no database,
Docker, or other infrastructure, and finishes in a few seconds. Persistence integration tests
are excluded by naming convention (`*Test` vs `*IT`, see below).

### Persistence integration tests

`JdbcPromotionRepositoryIT` (`src/test/java/.../infrastructure/persistence/`) exercises
`JdbcPromotionRepository` against a real, disposable PostgreSQL instance started automatically by
[Testcontainers](https://testcontainers.com/) — no manual database setup is required, but
**Docker must be installed and running** (the container image `postgres:16-alpine` is pulled and
started/stopped per test run).

It's named `*IT` rather than `*Test` specifically so the default `./mvnw test` (Surefire) skips
it; only Maven's `verify` lifecycle phase (bound to the Failsafe plugin) runs it:

```bash
./mvnw verify
```

To run just the integration tests without the rest of the `verify` lifecycle:

```bash
./mvnw failsafe:integration-test
```

What it proves: the adapter's SQL round-trips every field of a `Promotion` (including the
optional `fromEnvironment`/`approvedBy`), the aggregate can keep applying its own guarded
transitions across a save → reload cycle, and the two invariant-scoped queries
(`findActivePromotionsForTarget`, `findLastCompletedEnvironment`) filter correctly at the SQL
level — including a couple of tests that replay the exact repository-then-aggregate call sequence
`PromotionCommandService` uses, so a passing run means the adapter and the domain layer genuinely
agree on how a `Promotion` invariant is enforced across a reload, not just that each side works in
isolation.

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
It is not yet wired into the Spring context — nothing in the application
depends on a live `DataSource` until the HTTP layer is connected to the
command handlers, so `ReleasePilotApplication` excludes
`DataSourceAutoConfiguration` for now and the adapter is exercised directly
by `JdbcPromotionRepositoryIT` (see "Persistence integration tests" above).

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
notifications, ...) declares and binds its own queue. Like
`JdbcPromotionRepository`, neither `OutboxEventPublisher` nor `OutboxRelay` is
wired into the Spring context yet; both are exercised directly by
`OutboxEventPublisherIT` and `OutboxRelayIT` (Testcontainers Postgres +
RabbitMQ — the latter proves an event genuinely survives the outbox → queue
hop and isn't redelivered on a second relay pass).

## Project structure

```
src/main/java/com/releasepilot/
  domain/          # Promotion aggregate, value objects, invariants, ports
  application/      # Command and query handlers
  infrastructure/    # Persistence, in-memory adapters, message queue
  consumers/         # Async event consumers (audit log, projections, notifications)
  api/                # Thin REST controllers
src/main/resources/
  db/migration/       # Flyway SQL migrations
  application.yml
```
