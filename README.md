# Spring Boot Idempotency Starter

[![CI](https://github.com/KHolodilin/spring-boot-idempotency-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/KHolodilin/spring-boot-idempotency-starter/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/KHolodilin/spring-boot-idempotency-starter/branch/main/graph/badge.svg)](https://codecov.io/gh/KHolodilin/spring-boot-idempotency-starter)
[![Maven Central](https://img.shields.io/maven-central/v/com.kholodilin/spring-boot-idempotency-starter.svg?label=maven-central)](https://central.sonatype.com/artifact/com.kholodilin/spring-boot-idempotency-starter)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Transactional idempotency for Spring Boot 4 / Java 21: a repeated request with the same
`Idempotency-Key` does not execute the business operation again — it replays the stored
outcome of the first execution, including deterministic business rejections.

The key idea: the idempotency record is committed **in the same transaction** as the
business changes. A rollback also rolls the record back — half-committed states are
impossible.

## Modules

| Module | Purpose |
|---|---|
| `idempotency-core` | Domain model, SPI, `DefaultIdempotencyService`, canonical JSON fingerprint, Jackson serialization |
| `idempotency-persistence-jdbc` | `PersistenceStore` for PostgreSQL (`JdbcClient`), schema management |
| `idempotency-local-cache-caffeine` | L1 cache (Caffeine) — fast replays, hot-key protection |
| `idempotency-distributed-cache-redis` | L2 cache (Redis, fail-open) — shared across application instances |
| `spring-boot-idempotency-starter` | Auto-configuration, configuration properties, Micrometer metrics |
| `idempotency-demo` | Runnable demo: REST API, docker-compose, all scenarios |

## Architecture

```mermaid
flowchart LR
    C[Controller] --> S["PaymentService<br/>@Transactional"]
    S --> I[IdempotencyService]
    I --> L1["L1 Caffeine<br/>(optional)"]
    I --> L2["L2 Redis<br/>(optional, fail-open)"]
    I --> PG[("PostgreSQL<br/>source of truth")]
    I --> A["business action<br/>Supplier&lt;ExecutionResult&gt;"]
```

Execution flow of `operation(...).execute(...)`:

1. The request fingerprint is calculated (canonical JSON + SHA-256).
2. Cache lookup: L1 → L2 (a hit in L2 is promoted to L1).
3. Optional persistence find when `idempotency.persistence.lookup-before-acquire=true`
   (default is `false`: insert-first).
4. Cache/optional-find miss → `INSERT ... ON CONFLICT DO NOTHING`. On conflict the
   service finds the committed terminal row and replays it. A concurrent duplicate
   blocks on the unique index until the first transaction commits or rolls back.
5. Matching fingerprint → replay (action **not** executed). Different fingerprint →
   `IdempotencyConflictException`.
6. The action returns an `ExecutionResult`: `Success` → `COMPLETED`, `Rejected` →
   `REJECTED`. The outcome is persisted in the caller's transaction.
7. A technical exception from the action propagates → rollback → no record → a retry
   executes the operation from scratch.
8. After the commit (and only then) the outcome is written to Redis and Caffeine.

Rows remain replayable until physically deleted. `expires_at` is only a cleanup marker
(from `persistence.ttl`, or a per-call `.ttl(...)` override on acquire); it is not
consulted on the request path.

## Quick start

Maven:

```xml
<dependency>
    <groupId>com.kholodilin</groupId>
    <artifactId>spring-boot-idempotency-starter</artifactId>
    <version>0.3.4</version>
</dependency>

<!-- optional: L1 cache -->
<dependency>
    <groupId>com.kholodilin</groupId>
    <artifactId>idempotency-local-cache-caffeine</artifactId>
    <version>0.3.4</version>
</dependency>

<!-- optional: L2 cache (requires a RedisConnectionFactory, e.g. via spring-boot-starter-data-redis) -->
<dependency>
    <groupId>com.kholodilin</groupId>
    <artifactId>idempotency-distributed-cache-redis</artifactId>
    <version>0.3.4</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.kholodilin:spring-boot-idempotency-starter:0.3.4")

// optional caches
implementation("com.kholodilin:idempotency-local-cache-caffeine:0.3.4")
implementation("com.kholodilin:idempotency-distributed-cache-redis:0.3.4")
```

A PostgreSQL `DataSource` in the context is all it takes — the starter assembles the
`IdempotencyService` automatically. The cache modules activate simply by being present
on the classpath.

### Service

```java
@Service
public class PaymentService {

    private final IdempotencyService idempotencyService;

    @Transactional
    public ExecutionResult<PaymentResult> createPayment(String key, CreatePaymentRequest request) {
        return idempotencyService
                .operation("CREATE_PAYMENT")
                .key(key)
                .request(request)
                // optional: override persistence.ttl for this acquire only
                // .ttl(Duration.ofDays(30))
                .execute(PaymentResult.class, () -> {
                    if (request.amount().compareTo(balance) > 0) {
                        // deterministic business rejection: persisted and replayed on duplicates
                        return ExecutionResult.rejected("INSUFFICIENT_FUNDS",
                                new InsufficientFundsDetails(request.amount(), balance));
                    }
                    paymentRepository.insert(...); // business changes in the same transaction
                    return ExecutionResult.success(new PaymentResult(...));
                });
    }
}
```

### Controller: `valueOrThrow()` + a global handler

```java
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.exception.IdempotencyRejectedException;

@PostMapping("/payments")
@ResponseStatus(HttpStatus.CREATED)
PaymentResult create(@RequestHeader("Idempotency-Key") String key,
                     @RequestBody CreatePaymentRequest request) {
    return paymentService.createPayment(key, request).valueOrThrow();
}

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IdempotencyRejectedException.class)   // business rejection (422)
    ResponseEntity<?> onRejected(IdempotencyRejectedException e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("code", e.errorCode(), "details", e.details()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)   // same key, different payload (409)
    ResponseEntity<?> onConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "IDEMPOTENCY_KEY_CONFLICT"));
    }
}
```

`valueOrThrow()` throws **outside** the transaction — a business rejection can never
cause a rollback, so `REJECTED` is committed and replayed correctly.

### Alternative: `fold()`

```java
return paymentService.refund(key, request).fold(
        ResponseEntity::ok,
        rejected -> ResponseEntity.unprocessableEntity()
                .body(Map.of("code", rejected.errorCode(), "details", rejected.details())));
```

Typed access to rejection details: `rejected.detailsAs(InsufficientFundsDetails.class)`.

## Configuration

```yaml
idempotency:
  enabled: true                        # master switch

  fingerprint:
    algorithm: SHA-256                 # digest algorithm of the canonical JSON fingerprint

  local-cache:                         # requires idempotency-local-cache-caffeine
    enabled: true
    ttl: 10m
    max-size: 10000
    statistics: false

  distributed-cache:                   # requires idempotency-distributed-cache-redis + RedisConnectionFactory
    enabled: true
    ttl: 1h
    key-prefix: "idempotency:"
    failure-policy: fail-open          # fail-open | fail-fast

  persistence:
    enabled: true
    table-name: idempotency_records    # may be schema-qualified: billing.idempotency_records
    ttl: 365d                          # default expires_at marker; override per call with .ttl(...)
    lookup-before-acquire: false       # true = DB find before INSERT (better cold-duplicate latency)
    schema:
      mode: validate                   # create | validate | none
    cleanup:
      enabled: false                   # recommended true in production
      cron: "0 0 3 * * SAT,SUN"
      batch-size: 1000
```

### Schema management

- `create` — the starter executes the canonical DDL at startup (convenient for dev/demo);
- `validate` — recommended for production: the application fails fast at startup if the
  table is missing or incompatible, while you run the migration yourself (Flyway/Liquibase);
- `none` — the starter does nothing.

The canonical DDL lives at
`idempotency-persistence-jdbc/src/main/resources/com/kholodilin/idempotency/jdbc/idempotency-records.sql` —
copy it into your migrations:

```sql
CREATE TABLE IF NOT EXISTS idempotency_records (
    operation        VARCHAR(128)  NOT NULL,
    idempotency_key  VARCHAR(255)  NOT NULL,
    request_hash     VARCHAR(128)  NOT NULL,
    status           VARCHAR(32)   NOT NULL,   -- PROCESSING | COMPLETED | REJECTED
    result_type      VARCHAR(255),
    result_payload   JSONB,
    error_code       VARCHAR(128),
    created_at       TIMESTAMPTZ   NOT NULL,
    completed_at     TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    PRIMARY KEY (operation, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_expires_at ON idempotency_records (expires_at);
```

### Overriding components

Any SPI bean replaces the default one (every auto-configured bean is
`@ConditionalOnMissingBean`):

```java
@Bean
FingerprintStrategy fingerprintStrategy() { ... }      // custom fingerprint strategy

@Bean
PersistenceStore persistenceStore() { ... }            // custom persistence

@Bean
LocalCache localCache() { ... }

@Bean
DistributedCache distributedCache() { ... }

@Bean
IdempotencySerializer idempotencySerializer() { ... }

@Bean
TransactionContext transactionContext() { ... }        // default: SpringTransactionContext

@Bean
IdempotencyMetrics idempotencyMetrics() { ... }         // default: Micrometer when MeterRegistry present
```

### Metrics (Micrometer)

When a `MeterRegistry` is present, the following meters are registered automatically:
`idempotency.lookup.hits{level}`, `idempotency.replays{status}`, `idempotency.conflicts`,
`idempotency.acquired`, `idempotency.acquire.conflicts`, `idempotency.acquire.wait`,
`idempotency.persisted{status}`.

## Demo

```bash
cd idempotency-demo
docker compose up -d          # PostgreSQL + Redis (Redis is optional)
mvn spring-boot:run
```

```bash
# first request — the payment is created
curl -X POST localhost:8080/api/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-1" \
  -d '{"orderId": "o-1", "recipient": "alice", "amount": 100.00}'

# duplicate — same paymentId, the action is not executed
curl -X POST localhost:8080/api/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-1" \
  -d '{"orderId": "o-1", "recipient": "alice", "amount": 100.00}'

# same key, different payload → 409
curl -X POST localhost:8080/api/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-1" \
  -d '{"orderId": "o-1", "recipient": "alice", "amount": 200.00}'

# business rejection → 422, a repeat returns the same rejection
curl -X POST localhost:8080/api/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-2" \
  -d '{"orderId": "o-2", "recipient": "alice", "amount": 5000.00}'

# technical failure → 500 + rollback, a retry with the same key executes from scratch
curl -X POST localhost:8080/api/payments \
  -H "Content-Type: application/json" -H "Idempotency-Key: demo-3" \
  -d '{"orderId": "o-3", "recipient": "FAIL_ONCE", "amount": 100.00}'
```

## FAQ

**Why is an active transaction required?**
The idempotency record and the business changes must commit atomically. Without a
transaction it is possible to persist an "outcome" without the business effect (or the
other way round). Calling outside a transaction throws `MissingTransactionException`.

**What happens if Redis is down?**
With the default `fail-open` policy — nothing: the error is logged, a read behaves as a
cache miss and the request falls through to PostgreSQL. Correctness never depends on the
caches — they only speed up replays.

**How is `Rejected` different from an exception?**
`Rejected` is a deterministic business outcome ("insufficient funds"): it is committed
and replayed on duplicates. A technical exception (timeout, deadlock) is a
non-deterministic failure: the transaction rolls back and the client can safely retry
with the same key.

**What happens with concurrent duplicates?**
The first request acquires the key (`INSERT ... ON CONFLICT DO NOTHING`), the second one
blocks on the unique index until the first transaction commits, then receives a replay
of its outcome. The business action executes exactly once.

**How do I clean up expired records?**
While a row exists it is replayed / conflicts — TTL does not hide it. Enable the built-in
job (`idempotency.persistence.cleanup.enabled=true`) or call
`IdempotencyPersistenceCleanup#deleteExpired`. JDBC cleanup uses
`FOR UPDATE SKIP LOCKED` so it does not block hot request transactions.

**When should I enable `lookup-before-acquire`?**
Default insert-first (`false`) avoids a DB round-trip on first-seen keys. Set
`idempotency.persistence.lookup-before-acquire=true` if cold duplicates are common and
you want a persistence find before `INSERT` (replays without waiting on PK conflict).

**Are result-less operations supported?**
Yes: `resultType = Void.class`, `ExecutionResult.success(null)`.

**Can I use a database other than PostgreSQL?**
Out of the box — PostgreSQL only (`ON CONFLICT DO NOTHING`, `JSONB`). For another
database implement your own `PersistenceStore` — the rest of the library is
dialect-agnostic.

## Requirements

- Java 21+
- Spring Boot 4.x (Jackson 3)
- PostgreSQL 13+

## Build

```bash
mvn clean verify     # integration tests require a running Docker daemon (Testcontainers)
```

The build enforces code format (Spotless / Palantir Java Format — run `mvn spotless:apply`
to fix), environment constraints (Maven Enforcer), javadoc validity and a minimum of
80% line coverage per library module (JaCoCo; the HTML report lands in
`<module>/target/site/jacoco/index.html`).

## Releasing

Push a tag — CI publishes signed artifacts to Maven Central and creates a GitHub Release:

```bash
git tag v0.1.1
git push origin v0.1.1
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
