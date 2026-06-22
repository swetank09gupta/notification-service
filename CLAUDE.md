# CLAUDE.md — Project AI Instructions

This file is loaded automatically by Claude Code at the start of every session.
It defines coding conventions, forbidden patterns, architectural invariants, and
common workflows for this project. Every developer on the team should keep this
file updated when conventions change.

---

## Build Commands

```bash
# Compile main sources
mvn compile -q

# Compile everything including tests
mvn test-compile -q

# Unit tests only (no Docker required) — 166 tests, ~15 s
mvn test -Dsurefire.excludes="**/integration/**"

# Full test suite with JaCoCo coverage check (requires Docker)
# On macOS with Docker Desktop, export DOCKER_HOST first:
#   export DOCKER_HOST=unix:///var/run/docker.sock
mvn verify

# Skip integration tests but still run JaCoCo report
mvn verify -Dsurefire.excludes="**/integration/**"

# Single integration test class
mvn test -Dtest="NotificationFlowIntegrationTest"

# Run app locally (requires PG + Redis + Kafka running)
DB_HOST=localhost REDIS_HOST=localhost KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  mvn spring-boot:run

# Full stack via Docker Compose (V2 syntax)
docker compose up --build
```

---

## Project Layout

```
src/main/java/com/dmg/notification/
├── channel/        ChannelDispatcher interface + one stub per channel
├── config/         AppProperties, KafkaConfig, RedisConfig, SecurityConfig
├── controller/     Auth, PlatformAdmin, TenantAdmin, Notification controllers
├── domain/         JPA entities — see docs/context/domain-model.md
├── domain/enums/   Channel, NotificationStatus, RequestStatus, AttemptStatus, UserRole
├── dto/            Request / response DTOs (no JPA annotations allowed here)
├── exception/      GlobalExceptionHandler, typed domain exceptions
├── kafka/          5-topic pipeline — see docs/context/kafka-topology.md
├── ratelimit/      RedisRateLimiterRegistry (Redis primary, TokenBucket fallback)
├── repository/     Spring Data JPA — one interface per aggregate root
├── scheduler/      NotificationScheduler (retry poller + schedule poller)
├── security/       JWT filter, JwtTokenProvider, UserPrincipal
├── service/        Domain services — one class per bounded context
└── worker/         BoundedDispatchPool (virtual threads + semaphores, retained)

src/test/java/com/dmg/notification/
├── integration/    BaseIntegrationTest + one test class per feature area
└── unit/           Pure unit tests, no Spring context, no Docker
```

---

## Coding Conventions

### General

- Java 21 features encouraged: records, sealed classes, pattern matching, text blocks.
- Prefer `List.of()` / `Map.of()` over mutable collections unless mutation is required.
- No `System.out.println` — use `@Slf4j` + `log.info/warn/error`.
- No raw `Exception` catches — catch the most specific type.

### Lombok Rules

- **Allowed**: `@Slf4j`, `@RequiredArgsConstructor`, `@Builder`, `@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`
- **Forbidden on classes with `transient` fields**: use explicit constructor + getters. `@Value` / `@Data` conflict with Java's `transient` keyword.
- **Forbidden on inner classes used as Spring `@ConfigurationProperties`**: Spring binding fails — write explicit getters/setters.
- `annotationProcessorPaths` is already configured in `pom.xml`. Do not add duplicate Lombok dependencies.

### Services

- Services are `@Transactional` at the method level, not class level (except read-only cases).
- Never inject a service into another service in a circular way. If you need cross-service calls, go through the repository layer or emit a Kafka event.
- `DispatchService.executeDispatch` must stay `@Transactional` — it reads, validates, and writes notification state atomically.
- Never call `dispatchService` from `NotificationService`. `NotificationService` only publishes Kafka events; the consumer calls `DispatchService`.

### Controllers

- Controllers validate tenant access via `assertTenantAccess(principal, tenantId)` — this must be the first line of every handler.
- Controllers must not contain business logic — delegate to services.
- Controllers must not reference repositories directly (exception: `NotificationController.getDeliveries` which returns raw entities — acceptable for an internal read).

### DTOs

- DTOs live in `dto/request/` or `dto/response/`. Never use JPA entities as REST response bodies.
- Use Bean Validation annotations (`@NotBlank`, `@Size`, `@Valid`) on request DTOs.
- Response DTOs use static `from(Entity entity)` factory methods, not constructors.

### Database / Flyway

- Never use `ddl-auto: create` or `update`. Always `validate`.
- All schema changes go through a new Flyway migration file: `V{n+1}__{description}.sql`.
- Use `IF NOT EXISTS` / `IF EXISTS` in migrations for idempotency.
- Migration filenames: `V3__add_xyz_column.sql` (double underscore, descriptive).
- See `.claude/commands/add-migration.md` for the full checklist.

---

## Architectural Invariants — DO NOT VIOLATE

1. **Tenant isolation is mandatory.** Every DB query that touches tenant-owned data must include `tenantId` in the WHERE clause. Never return data from another tenant. See `docs/context/tenant-isolation.md`.

2. **Idempotency key uniqueness.** `notification_requests.idempotency_key` has a UNIQUE constraint. The `DuplicateRequestException` path must never be bypassed.

3. **`DELIVERED` is terminal.** Once `notification.status == DELIVERED`, `executeDispatch` must return immediately. This is the duplicate-delivery guard on retry.

4. **Attempt count is only incremented on an actual dispatch attempt.** Prerequisite-waiting reschedules must NOT increment `attemptCount`. Rate-limit reschedules DO increment.

5. **Circuit breaker keys must be lowercase channel names.** `circuitBreakerRegistry.circuitBreaker("email")` — the Resilience4j `instances:` keys in `application.yml` are lowercase.

6. **Kafka partition keys.** `notification.requests` and `notification.status` partition by `correlationId` for per-order ordering. `notification.dispatch` and `notification.retry` partition by `tenantId` for per-tenant fairness. Never change these without updating `docs/adr/ADR-006.md`.

7. **Never throw from `DispatchService.executeDispatch`.** All outcomes are modelled as DB state + Kafka status events. Uncaught exceptions break the `@Transactional` boundary and leave notifications stuck in `PROCESSING`.

---

## Code Quality Rules (enforced by pre-commit + CI)

### Test Coverage
- Target: **≥ 90% line coverage** on unit + integration tests combined
- Minimum enforced by JaCoCo on `mvn verify`: **80% line, 75% branch**
- Excluded from coverage: `NotificationServiceApplication`, `dto/**`, `domain/enums/**`, `*Exception.class`
- Run locally: `mvn verify` (requires Docker). Report: `target/site/jacoco/index.html`
- Pre-commit runs only unit + arch tests (no Docker). Full coverage check is for `mvn verify` / CI.

### TODO / FIXME Policy
- **Every TODO must resolve to one of:**
  1. A failing test that documents the missing behaviour (`@Disabled("TODO: implement X")`)
  2. A tracking ticket reference (`// TODO NOTIF-123: migrate to sliding window`)
  3. Fixed in the same PR
- Bare `// TODO` or `// FIXME` without a ticket or test **blocks the pre-commit hook**.
- `// HACK` and `// XXX` are treated the same as TODO.

### New Code Must Have Tests
- New `Service` class → unit test (mock dependencies, no Spring context)
- New `ChannelDispatcher` → unit test covering success, transient failure, permanent failure
- New business rule in `DispatchService` → integration test verifying DB state outcome
- New REST endpoint → integration test verifying response code + body

### Observability for New Features
- New metrics in `NotificationMetrics` for any new status outcome or new dispatch path
- MDC context populated before any meaningful log.info/warn/error call in dispatch paths
- New channels must emit `recordDispatch`, `recordDlq` calls through `NotificationMetrics`

---

## Forbidden Patterns

```java
// NEVER — use @Slf4j and log instead
System.out.println("...");

// NEVER — catch raw Exception unless you immediately re-throw with context
catch (Exception e) { log.error("oops"); }

// NEVER — bypass tenant check
return notificationRepository.findAll(); // returns ALL tenants' data

// NEVER — call the DB from a controller
@GetMapping("/x") public X get() { return repo.findAll(); } // use a service

// NEVER — add a new Flyway migration without testing rollback impact
// (see .claude/commands/add-migration.md)

// NEVER — store secrets in application.yml
jwt.secret: my-literal-secret  // use ${JWT_SECRET} env var

// NEVER — use @Transactional(propagation = REQUIRES_NEW) without a documented reason
// it creates a nested transaction that can leave DB in inconsistent state on outer rollback
```

---

## Adding New Features

| Feature type | Start here |
|---|---|
| New notification channel | `.claude/commands/add-channel.md` |
| New Flyway migration | `.claude/commands/add-migration.md` |
| New integration test | `.claude/commands/add-integration-test.md` |
| Tenant isolation audit | `.claude/commands/check-tenant-isolation.md` |

---

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `notification_db` | Database name |
| `DB_USER` | `postgres` | DB user |
| `DB_PASSWORD` | `postgres` | DB password |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap |
| `KAFKA_CONSUMER_CONCURRENCY` | `5` | Listener thread count |
| `JWT_SECRET` | (insecure dev default) | HS256 signing key — **override in prod** |

---

## References

- Architecture decisions: `docs/adr/`
- Domain model: `docs/context/domain-model.md`
- Tenant isolation rules: `docs/context/tenant-isolation.md`
- Kafka topology: `docs/context/kafka-topology.md`
- Observability (metrics, MDC, alerts, Grafana): `docs/context/observability.md`
- Production scale limits and SLAs: `docs/context/production-scale.md`
- Partitioning strategy (1PB scale): `docs/context/partitioning-strategy.md`
