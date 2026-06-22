Review the current git diff for correctness, security, and architectural alignment.

## What to check

### 1. Tenant isolation (CRITICAL)
- Every new controller handler calls `assertTenantAccess` as its first line
- Every new repository finder that returns tenant data has `tenantId` in the WHERE clause
- No `findAll()` on tenant-scoped repositories without a tenant filter
- See `docs/context/tenant-isolation.md` for the full checklist

### 2. Architectural invariants (see CLAUDE.md)
- `DispatchService.executeDispatch` never throws — all outcomes modelled as DB state
- `NotificationService` never calls `DispatchService` — publishes Kafka event instead
- Circuit breaker keys are lowercase
- Kafka partition keys follow the strategy in ADR-006

### 3. Coding conventions
- No Lombok `@Value` or `@Data` on classes with `transient` fields
- No Lombok on `@ConfigurationProperties` inner classes
- No `System.out.println`
- Exceptions caught at the right specificity
- DTOs have no JPA annotations
- Response DTOs use `from(Entity)` factory methods

### 4. Database changes
- Any schema change has a Flyway migration (no `ddl-auto: update`)
- Migration file uses `IF NOT EXISTS` / `IF EXISTS`
- New non-nullable columns have a DEFAULT
- See `.claude/commands/add-migration.md`

### 5. Tests
- New features have at least one integration test
- Async assertions use Awaitility, not `Thread.sleep`
- Test method names follow `<action>_<condition>_<outcome>` convention

### 6. Security
- No secrets hardcoded (use env vars)
- No SQL injection risk (use named parameters / JPA, not string concatenation)
- JWT secret uses `${JWT_SECRET}` env var

## Commands to run before reviewing
```bash
# Compile check
mvn compile -q && mvn test-compile -q

# Unit tests
mvn test -Dtest="TokenBucketTest,TemplateEngineTest,BoundedDispatchPoolTest"

# Show changed files
git diff --name-only
```
