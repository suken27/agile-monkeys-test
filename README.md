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
