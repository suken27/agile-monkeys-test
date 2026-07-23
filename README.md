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
