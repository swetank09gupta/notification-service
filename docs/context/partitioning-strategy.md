# PostgreSQL Partitioning Strategy (1PB Scale)

Converting to declarative table partitioning requires a maintenance window and a data migration.
This document defines the target schema. Apply it when the active dataset approaches 500M+ rows.

---

## Why Partitioning

| Without partitioning | With partitioning |
|---|---|
| Full table scan for time-range reports | Partition pruning — PostgreSQL touches only relevant date partitions |
| VACUUM on 2B-row table blocks writes | Per-partition VACUUM runs independently, non-blocking |
| DROP old data = slow DELETE + VACUUM | DROP PARTITION — instant, no VACUUM needed |
| Single B-tree index covers all rows | Per-partition indexes are smaller and stay in page cache |
| pg_dump of full table takes hours | Dump one partition at a time |

At 1PB: ~2 trillion notification rows (at ~500 bytes/row).
Monthly RANGE partitions → ~170B rows/month at 1PB/year.
Each partition index fits in L3 cache → query latency stays low even as data grows.

---

## Partitioning Design

### `notifications` — two-level partitioning

```
notifications (partitioned by RANGE created_at)
├── notifications_2026_06  (2026-06-01 to 2026-07-01)
│   ├── notifications_2026_06_t0  (tenant_id hash bucket 0 of 8)
│   ├── notifications_2026_06_t1
│   ...
│   └── notifications_2026_06_t7
├── notifications_2026_07
...
```

**Level 1:** Monthly RANGE on `created_at` — enables partition pruning for all time-range queries
**Level 2:** HASH on `tenant_id` with 8 buckets — distributes hot-tenant writes across physical segments

### `delivery_attempts` — single-level partitioning

```
delivery_attempts (partitioned by RANGE attempted_at)
├── delivery_attempts_2026_06
├── delivery_attempts_2026_07
...
```

### `notification_requests` — single-level partitioning

```
notification_requests (partitioned by RANGE created_at)
├── notification_requests_2026_06
...
```

---

## Target DDL

```sql
-- notifications: RANGE by month → HASH by tenant
CREATE TABLE notifications_partitioned (
    id                UUID NOT NULL,
    request_id        UUID NOT NULL,
    tenant_id         UUID NOT NULL,
    channel           VARCHAR(20) NOT NULL,
    recipient_address VARCHAR(500) NOT NULL,
    rendered_subject  VARCHAR(500),
    rendered_body     TEXT NOT NULL,
    status            VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count     INT NOT NULL DEFAULT 0,
    max_attempts      INT NOT NULL DEFAULT 5,
    next_retry_at     TIMESTAMP,
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)   -- partition key must be in PK
) PARTITION BY RANGE (created_at);

-- Monthly partitions (automate with pg_partman extension)
CREATE TABLE notifications_2026_06
    PARTITION OF notifications_partitioned
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01')
    PARTITION BY HASH (tenant_id);

-- Tenant hash sub-partitions per month
CREATE TABLE notifications_2026_06_t0
    PARTITION OF notifications_2026_06
    FOR VALUES WITH (MODULUS 8, REMAINDER 0);
-- ... t1 through t7 ...

-- Indexes are defined on the parent and inherited by all partitions:
CREATE INDEX ON notifications_partitioned(tenant_id, status, channel);
CREATE INDEX ON notifications_partitioned(next_retry_at, tenant_id)
    WHERE status IN ('FAILED', 'RATE_LIMITED') AND next_retry_at IS NOT NULL;
CREATE INDEX ON notifications_partitioned USING BRIN(created_at);


-- delivery_attempts: RANGE by month
CREATE TABLE delivery_attempts_partitioned (
    id               UUID NOT NULL,
    notification_id  UUID NOT NULL,
    attempt_number   INT NOT NULL,
    status           VARCHAR(30) NOT NULL,
    error_message    TEXT,
    channel_response TEXT,
    attempted_at     TIMESTAMP NOT NULL DEFAULT now(),
    duration_ms      BIGINT,
    PRIMARY KEY (id, attempted_at)
) PARTITION BY RANGE (attempted_at);

CREATE INDEX ON delivery_attempts_partitioned(notification_id, attempt_number);
CREATE INDEX ON delivery_attempts_partitioned USING BRIN(attempted_at);
```

---

## Partition Lifecycle (automate with pg_partman)

```sql
-- Create next month's partition in advance (run this at start of each month)
SELECT partman.create_partition_time(
    p_parent_table => 'public.notifications_partitioned',
    p_partition_times => ARRAY[now() + interval '1 month']
);

-- Archive partitions older than 12 months to cold storage
-- 1. pg_dump the partition: pg_dump -t notifications_2025_06 ...
-- 2. Upload to S3 / object storage
-- 3. DROP partition (instant — no DELETE + VACUUM):
ALTER TABLE notifications_partitioned
    DETACH PARTITION notifications_2025_06;
DROP TABLE notifications_2025_06;
```

---

## Migration Procedure (maintenance window required)

```sql
-- Step 1: Create partitioned table alongside existing one
CREATE TABLE notifications_new (...) PARTITION BY RANGE (created_at);
-- create all necessary partitions and indexes

-- Step 2: Bulk copy in batches (avoid long table lock)
INSERT INTO notifications_new
SELECT * FROM notifications
WHERE created_at >= '2026-01-01'  -- start with recent data first
  AND created_at <  '2026-02-01';
-- repeat for each month...

-- Step 3: Maintenance window — catch up remaining rows, swap tables
BEGIN;
LOCK TABLE notifications IN EXCLUSIVE MODE;  -- blocks writes briefly
INSERT INTO notifications_new SELECT * FROM notifications WHERE ...;  -- catch-up
ALTER TABLE notifications RENAME TO notifications_old;
ALTER TABLE notifications_new RENAME TO notifications;
COMMIT;

-- Step 4: Drop old table after validation
DROP TABLE notifications_old;
```

---

## Connection Pooling (mandatory at 10+ instances)

PostgreSQL's default `max_connections = 100`. With 10 app instances × 20 Hikari connections = 200 — exceeds the limit.

**Solution: PgBouncer in transaction mode**

```ini
# pgbouncer.ini
[databases]
notification_db = host=postgres port=5432 dbname=notification_db

[pgbouncer]
pool_mode = transaction       # connection returned to pool after each transaction
max_client_conn = 1000        # app connections PgBouncer accepts
default_pool_size = 20        # actual PostgreSQL connections PgBouncer maintains
server_idle_timeout = 60
```

With PgBouncer: 1000 app connections → 20 actual PostgreSQL connections.
Tune `default_pool_size` based on `max_connections` in `postgresql.conf`:
```
max_connections = 100
# Reserve 20 for admin/monitoring
# PgBouncer pool size = 80
```

**Hikari tuning per app instance** (reduces to 5 connections per instance when behind PgBouncer):
```yaml
spring.datasource.hikari:
  maximum-pool-size: 5      # PgBouncer handles multiplexing
  minimum-idle: 2
  connection-timeout: 3000  # fail fast if PgBouncer pool is exhausted
```

---

## Horizontal Read Scaling (read replicas)

Delivery reports (`GET /reports`) and notification list queries are read-heavy and can
be served by a read replica. Configure a separate datasource:

```yaml
app:
  datasource:
    read-replica-url: jdbc:postgresql://replica-host:5432/notification_db
```

Route `@Transactional(readOnly = true)` methods to the replica.
Spring's `AbstractRoutingDataSource` or a library like `datasource-proxy` handles this.
