# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A REST API for account-to-account money transfers (Java 21, Spring Boot 4, PostgreSQL + Flyway). Portfolio pet project targeting the Vienna banking/fintech job market. The centerpiece feature is **idempotency**: a client (conceptually a POS terminal on a flaky network) retries `POST /transfers` with the same `Idempotency-Key` header, and the server must not double-charge.

The project is being built in three phases; check `git log` / current code state to see how far it's gotten before assuming a feature exists:
- **Phase 1 (core)** — skeleton entities/endpoints → idempotency + optimistic-lock retry + concurrency tests → Dockerfile/CI/README packaging.
- **Phase 2 (extensions)** — Redis idempotency cache + rate limiting, Spring Security, Actuator/Prometheus/Grafana, Testcontainers. Only after phase 1 ships.
- **Phase 3** — a C client simulating a POS terminal (HTTP retries reusing the same idempotency key). Only after phase 1 gets interview traction.

Explicitly out of scope: Kafka/RabbitMQ, microservices, a frontend, Kubernetes, multi-currency conversion, a custom auth server.

## Commands

```bash
./gradlew build                # full build + tests
./gradlew compileJava compileTestJava   # fast compile-only check
./gradlew test                 # run all tests
./gradlew test --tests "com.slavaslava.transferapi.SomeTest"        # single test class
./gradlew test --tests "com.slavaslava.transferapi.SomeTest.someMethod"  # single test method
./gradlew bootRun               # run the app locally (needs Postgres — see below)
```

Windows: use `gradlew.bat` instead of `./gradlew` from PowerShell/cmd.

**Windows/Gradle gotcha on this machine**: the default `GRADLE_USER_HOME` (`C:\Users\<user>\.gradle`) contains Cyrillic characters, which breaks the forked test-worker JVM (`Could not find or load main class worker.org.gradle.process.internal.worker.GradleWorkerMain` — a JAR-manifest Class-Path encoding bug with non-ASCII paths). Work around it by pointing Gradle at an ASCII-only cache dir: `./gradlew test -g "D:/gradle-user-home"`. Only `test` (forked JVM workers) is affected; `compileJava`/`compileTestJava` work fine without the flag.

**Stack version note**: this project runs Spring Boot 4 / Spring Framework 7 / Jackson 3, which is newer than most published examples and tutorials assume. Notable breaking renames if something doesn't compile as expected: Jackson classes moved from `com.fasterxml.jackson.databind.*` to `tools.jackson.databind.*` (groupId `tools.jackson.core`); `@AutoConfigureMockMvc` moved from `org.springframework.boot.test.autoconfigure.web.servlet` to `org.springframework.boot.webmvc.test.autoconfigure`, and needs the `spring-boot-webmvc-test` test dependency (not bundled into `spring-boot-starter-test` the way it was in Boot 3). Don't assume Boot 3-era package names/artifacts — check the actual jar contents in `~/.gradle/caches` (or wherever `-g` points) if an import fails to resolve. Also: Hibernate 7 maps `java.time.Instant` to `TIMESTAMP_UTC` (`timestamp with time zone` on Postgres), so any timestamp column backing an `Instant` field must be `TIMESTAMPTZ` in the Flyway DDL or `ddl-auto=validate` aborts startup. `HttpStatus.UNPROCESSABLE_ENTITY` is deprecated in Spring 7 — use `UNPROCESSABLE_CONTENT`. Boot 4 also split `FlywayAutoConfiguration` out of `spring-boot-autoconfigure` into its own module — having `flyway-core` + `flyway-database-postgresql` on the classpath is not enough to trigger migrations at startup; the app also needs `implementation("org.springframework.boot:spring-boot-flyway")` (the same split pattern as `spring-boot-jdbc`/`spring-boot-jpa`/`spring-boot-hibernate`). Without it, Hibernate's `ddl-auto=validate` fails against an empty schema with `SchemaManagementException` because Flyway silently never ran — no error is logged about the missing autoconfiguration, so this is easy to misdiagnose as a schema/DDL bug.

The app needs a running Postgres to start (JPA `ddl-auto=validate`, schema owned by Flyway) or to run tests that load the Spring context:

```bash
docker compose up -d postgres
```

`docker-compose.yml` currently only defines the `postgres` service for local dev; an `app` service + `Dockerfile` are planned for the packaging phase, not added yet.

## Architecture

Layered, single-module Gradle project under `com.slavaslava.transferapi`:

- `domain/` — JPA entities. `Account` holds `balance` + `@Version` (optimistic locking) and owns its own invariant checks (`debit()` throws `InsufficientFundsException` on insufficient balance rather than the service checking balances directly). `Transfer` records a completed transfer including its `idempotencyKey` (failed attempts roll back and leave no row — there is no persisted FAILED state).
- `repository/` — plain Spring Data JPA repositories (`AccountRepository`, `TransferRepository`). `TransferRepository.findByIdempotencyKey` is the lookup point for idempotent replay.
- `service/` — business logic split in two: `TransferTransactionExecutor.execute` is the `@Transactional` core flow (same-account check → load both accounts → currency-match check → `from.debit()` / `to.credit()` → persist `Transfer`), while `TransferService.createTransfer` orchestrates around it (replay lookup, retry loop, duplicate-key race handling) and is deliberately non-transactional — see below.
- `web/` — `@RestController`s (`AccountController`, `TransferController`). Thin — validation (`@Valid`) and request/response mapping only, no business logic.
- `dto/` — request/response records. Responses have static `from(entity)` factory methods.
- `exception/` — domain exceptions (`AccountNotFoundException`, `InsufficientFundsException`, `CurrencyMismatchException`, `SameAccountTransferException`) plus `GlobalExceptionHandler`, a `@RestControllerAdvice` that maps them to RFC 7807 `ProblemDetail` responses. Bean-validation errors (`@Valid` failures) get RFC 7807 responses for free via `spring.mvc.problemdetails.enabled=true` — no custom handler needed for those.

Schema is managed entirely by Flyway migrations in `src/main/resources/db/migration/` (`V1__init.sql` so far); Hibernate is set to `validate` only, never to auto-generate DDL.

**Idempotency and concurrency (implemented)**: `TransferService.createTransfer` first looks up the `Idempotency-Key` in `TransferRepository`; if found, it verifies the incoming payload matches the stored transfer (from/to/amount) — a match replays the stored result (controller returns 200 instead of 201), a mismatch throws `IdempotencyKeyReuseException` → 422. Otherwise it calls `TransferTransactionExecutor.execute` (the actual `@Transactional` debit/credit/save) in a retry loop of up to 3 attempts, retrying on `OptimisticLockingFailureException` (an account's `@Version` changed under it) so each attempt reloads fresh account state. `TransferService.createTransfer` is deliberately **not** `@Transactional` itself — each retry attempt needs its own transaction/persistence context, otherwise stale entity versions would just get retried against. If two requests race with the same brand-new idempotency key, the DB's unique constraint makes the loser's insert throw `DataIntegrityViolationException`, which is caught and turned into a re-query (with the same payload-match check) instead of an error. `TransferTransactionExecutor.execute` must stay `public` — Spring's proxy-based `@Transactional` silently no-ops on non-public methods. The `Idempotency-Key` header is validated `@NotBlank @Size(max = 255)` at the controller.

**Known gaps (deliberate, phase-1 scope)**: `AccountService.deposit` mutates a `@Version`-guarded account but has **no** retry loop and no idempotency — a deposit racing a transfer on the same account surfaces as 409 Concurrent Modification, and a client retrying a timed-out deposit double-credits. Fixing it needs the same executor-split pattern as transfers.

Integration tests (`@SpringBootTest`) start their own PostgreSQL 16 via Testcontainers — each such test class is annotated `@Import(TestcontainersConfiguration.class)`, which pulls in a `@ServiceConnection`-annotated `PostgreSQLContainer` bean from `src/test/java/.../TestcontainersConfiguration.java`; Spring Boot auto-configures the datasource from the container, overriding `application.properties`. `./gradlew test` needs Docker but no running Postgres (compose or otherwise). Note: this project is on Testcontainers 2.x (managed by the Spring Boot 4.1 BOM), which renamed Maven artifacts with a `testcontainers-` prefix (`org.testcontainers:testcontainers-junit-jupiter`, `org.testcontainers:testcontainers-postgresql` — the old unprefixed `org.testcontainers:junit-jupiter`/`postgresql` coordinates exist on Maven Central only as legacy 1.x releases and won't resolve a version from the BOM) and moved `PostgreSQLContainer` to package `org.testcontainers.postgresql` (the old `org.testcontainers.containers.PostgreSQLContainer` still exists but is deprecated); it also dropped the generic self-type parameter, so it's `PostgreSQLContainer`, not `PostgreSQLContainer<?>`.
