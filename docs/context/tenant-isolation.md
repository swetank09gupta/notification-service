# Tenant Isolation Rules

Tenant isolation is the most critical correctness invariant in this service.
A data leak between tenants is a high-severity security incident.

---

## The Rule

**Every query that touches tenant-owned data MUST filter by `tenantId`.**

Tenant-owned entities: `User`, `Template`, `ChannelConfig`, `RateLimitConfig`,
`NotificationRequest`, `Notification`, `DeliveryAttempt`.

---

## Repository Patterns

### Always use tenant-scoped finder methods:

```java
// CORRECT
notificationRequestRepository.findByIdAndTenantId(requestId, tenantId);
notificationRepository.findAllByRequestIdAndTenantId(requestId, tenantId);
templateRepository.findByIdAndTenantId(templateId, tenantId);

// WRONG — returns data from all tenants
notificationRequestRepository.findById(requestId);
notificationRepository.findAll();
```

### Naming convention for repository methods:
All repository methods that filter by tenant must include `TenantId` in the name:
- `findByIdAndTenantId`
- `findAllByTenantId`
- `findAllByRequestIdAndTenantId`

If you add a repository method that does NOT include `TenantId`, document why (e.g., internal
scheduler queries that run outside tenant context).

---

## Controller Pattern

Every controller handler that operates in a tenant scope must call `assertTenantAccess`
as its first statement:

```java
@GetMapping("/{requestId}")
public NotificationRequestResponse get(@PathVariable UUID tenantId,
                                        @PathVariable UUID requestId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
    assertTenantAccess(principal, tenantId);  // ← FIRST LINE, always
    return notificationService.getRequest(tenantId, requestId);
}
```

`assertTenantAccess` allows the call if:
- The principal is a PLATFORM_ADMIN (can access any tenant), OR
- The principal's `tenantId` matches the path `tenantId`

Never bypass this check for "convenience" or "internal" endpoints.

---

## Service Pattern

Services receive `tenantId` as an explicit parameter and pass it through to every repository call.

```java
// CORRECT — tenantId threaded through
public NotificationRequestResponse getRequest(UUID tenantId, UUID requestId) {
    return requestRepository.findByIdAndTenantId(requestId, tenantId)
            .orElseThrow(() -> new NoSuchElementException("Request not found: " + requestId));
}

// WRONG — only checks requestId, ignores tenantId
public NotificationRequestResponse getRequest(UUID requestId) {
    return requestRepository.findById(requestId)  // ← leaks cross-tenant data
            .orElseThrow(...);
}
```

---

## Security Model

- JWT tokens contain `tenantId` and `role` claims.
- `UserPrincipal.getTenantId()` returns the tenant from the JWT — it is the authoritative
  source for tenant isolation in the request context.
- Platform admins have `tenantId = null` in the JWT. The `assertTenantAccess` method
  explicitly allows null tenantId for PLATFORM_ADMIN role.

---

## Audit Checklist for New Code

Before merging any change that touches data access:

- [ ] Every new repository method that returns tenant data includes `tenantId` in its WHERE clause.
- [ ] Every new controller handler calls `assertTenantAccess` as the first statement.
- [ ] No new `findAll()` calls on tenant-owned repositories without a `tenantId` filter.
- [ ] Integration tests verify that tenant A cannot access tenant B's data.

Use `/check-tenant-isolation` (Claude Code skill) to run an automated audit on changed files.
