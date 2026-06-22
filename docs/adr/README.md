# Architecture Decision Records

This directory captures the key architectural decisions made during the design and
implementation of the Multi-tenant Notification Service.

Each ADR follows the lightweight format:
- **Status**: Proposed / Accepted / Deprecated / Superseded
- **Context**: The situation or problem that required a decision
- **Decision**: What was decided
- **Consequences**: The resulting tradeoffs

| ID | Title | Status |
|---|---|---|
| [ADR-001](ADR-001-virtual-threads.md) | Java 21 Virtual Threads over Reactive | Accepted |
| [ADR-002](ADR-002-kafka-async-pipeline.md) | Kafka for Async Dispatch Pipeline | Accepted |
| [ADR-003](ADR-003-redis-rate-limiting.md) | Redis Lua for Distributed Rate Limiting | Accepted |
| [ADR-004](ADR-004-resilience4j-circuit-breakers.md) | Resilience4j Circuit Breakers per Channel | Accepted |
| [ADR-005](ADR-005-kafka-first-ingestion.md) | Kafka-first Ingestion (no HTTP required) | Accepted |
| [ADR-006](ADR-006-partition-strategy.md) | Kafka Partition Key Strategy | Accepted |
| [ADR-007](ADR-007-db-driven-retry.md) | DB-driven Retry Scheduling | Accepted |
| [ADR-008](ADR-008-horizontal-scaling.md) | Horizontal Scaling Design | Accepted |
