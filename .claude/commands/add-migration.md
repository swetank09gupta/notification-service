Add a new Flyway database migration safely.

## When to use
Any time the DB schema needs to change: adding a table, column, index, or constraint.

## Steps

### 1. Find the next migration version
```bash
ls src/main/resources/db/migration/
```
The next version is the highest `V{n}` + 1.

### 2. Create the migration file
`src/main/resources/db/migration/V{n+1}__{short_description}.sql`

Naming rules:
- Double underscore between version and description: `V3__add_priority_column.sql`
- Snake_case description, lowercase
- Describe what changes, not why

### 3. Write the migration defensively

```sql
-- Adding a column:
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS priority SMALLINT NOT NULL DEFAULT 0;

-- Adding an index:
CREATE INDEX IF NOT EXISTS idx_notifications_priority
    ON notifications(priority)
    WHERE priority > 0;

-- Adding a table:
CREATE TABLE IF NOT EXISTS new_table (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ...
);
```

Rules:
- Always use `IF NOT EXISTS` / `IF EXISTS` for idempotency
- Never use `DROP TABLE` or `DROP COLUMN` without an `IF EXISTS`
- New non-nullable columns must have a `DEFAULT` value for existing rows
- Add indexes in a separate migration from the table/column change (prevents table lock on large tables)

### 4. Update the JPA entity
Edit the corresponding `@Entity` class to add the new field.

For new columns:
```java
@Column(name = "priority", nullable = false)
private int priority = 0;
```

### 5. Update the JPA repository if needed
Add finder methods following the `TenantId`-scoped naming convention.

### 6. Run validation
```bash
# Compile
mvn compile -q

# Run unit tests (no DB required)
mvn test -Dtest="TokenBucketTest,TemplateEngineTest,BoundedDispatchPoolTest"

# Run integration tests to verify Flyway applies migration correctly
# Testcontainers starts a clean DB each time — migration is tested from scratch
mvn test -Dtest="NotificationFlowIntegrationTest"
```

### 7. Document significant schema changes
If the migration changes a key invariant (e.g., adding a new status value, changing a UNIQUE
constraint), update `docs/context/domain-model.md`.

## Checklist
- [ ] Migration file named with correct version number and double underscore
- [ ] Uses `IF NOT EXISTS` / `IF EXISTS` throughout
- [ ] New non-nullable columns have a DEFAULT
- [ ] JPA entity updated to match
- [ ] `mvn compile -q` passes (Flyway validates schema at startup)
- [ ] Integration test run passes (Flyway actually applies the migration in a clean DB)
- [ ] `docs/context/domain-model.md` updated if schema semantics changed
