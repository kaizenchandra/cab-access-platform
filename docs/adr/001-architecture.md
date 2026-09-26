# ADR 001: modular monolith and explicit transactions

Accepted for MVP. Java 21, Spring Boot 4.1.1, WebFlux boundary; blocking JPA/JDBC work is scheduled on boundedElastic. `TenantTx` creates bounded transactions inside that worker and sets PostgreSQL transaction-local tenant context. No Reactor-context-to-thread-local assumption. JPA owns immutable plan persistence; explicit SQL handles row locks, outbox claims and reporting. Capability services are called through application methods; controllers perform no provider I/O inside a transaction.

PostgreSQL is authoritative. Forced RLS, composite tenant foreign keys, runtime non-owner/non-BYPASSRLS role and per-resource authorization provide layered isolation. Tenant selectors alone grant nothing. Infrastructure migration credentials are separate. Global routing tables contain only trusted tenant/device/merchant mappings; they are never tenant data API endpoints. Platform administrator has onboarding permission, not universal data access.

Outbox jobs are at-least-once, claimed with SKIP LOCKED, leases and bounded retries. Business keys and conditional transitions make fulfillment idempotent. External write ambiguity is reconciled instead of assuming exactly-once. Vehicle/facility subscription row locks serialize renewals; an order has one fulfillment key. Redis/Kafka/microservices add no MVP value and are deferred.

Plan terms/prices/zone coverage become immutable versions and quote/order/entitlement snapshots. Collections retain tenant merchant and beneficiary; settlements remain separate. Minor-unit integers prevent binary floating-point money errors.

Gate decisions, command dispatch, command acknowledgements and physical passage are separate evidence. A dispatcher claims a command once before sending; a crash after claim yields UNKNOWN/manual review, not physical command replay. Offline admission fails closed to the manual process; emergency hardware behavior is out of scope.
