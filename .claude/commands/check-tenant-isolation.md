Audit the current changes for tenant isolation violations.

## What this checks

1. Repository methods that touch tenant-owned data but do not filter by `tenantId`
2. Controller handlers missing the `assertTenantAccess` call
3. Service methods that accept a request body but do not thread `tenantId` through to the DB query
4. Any `findAll()` call on tenant-scoped repositories

## Tenant-owned entities to check

- `User`
- `Template`
- `ChannelConfig`
- `RateLimitConfig`
- `NotificationRequest`
- `Notification`
- `DeliveryAttempt`

## Audit steps

Search the changed files for each of these patterns:

### 1. Controller handlers missing tenant check
```bash
grep -n "@GetMapping\|@PostMapping\|@PutMapping\|@PatchMapping\|@DeleteMapping" \
  src/main/java/com/dmg/notification/controller/*.java
```
For each handler in `TenantAdminController` and `NotificationController`,
verify the next non-annotation line calls `assertTenantAccess(principal, tenantId)`.

### 2. Repository `findAll()` without filter
```bash
grep -rn "\.findAll()" src/main/java/com/dmg/notification/
```
Any `findAll()` on a tenant-scoped repository is a violation unless it is:
- In `PlatformAdminController` (platform admin sees all)
- In a scheduler method explicitly designed to run cross-tenant (document why)

### 3. Repository finder methods without TenantId
```bash
grep -n "find\|query" src/main/java/com/dmg/notification/repository/*.java
```
Every finder that returns tenant data should have `AndTenantId` in its name and
`tenantId` in its `@Query` WHERE clause.

### 4. Service methods missing tenantId parameter
Check that every service method that accesses DB records passes `tenantId` explicitly.
Methods receiving only an entity ID (UUID) without tenantId are suspicious.

## Reference
See `docs/context/tenant-isolation.md` for the full isolation rules and acceptable patterns.
