# Implementation status

Current phase: 7 — hardening and verification; deployment artifacts in progress.

## Repository and environment
Empty backend repository; pre-existing staged IntelliJ files are preserved. Java 21 installed at `/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`. Maven 3.9.16 and Helm 4.3 available. Docker CLI and OrbStack now run successfully after starting outside the restricted shell. Terraform absent. No provider, production identity, notification, gate, or AWS credentials supplied.

## Execution plan
0. Record requirements, traceability, assumptions, dependencies.
1. Define capability boundaries, relational integrity, permissions, threats, API and sequences.
2. Build Java 21/WebFlux/JPA/PostgreSQL/Flyway foundation, OIDC and RLS.
3. Implement onboarding, eligibility, immutable plans and quotes.
4. Implement durable payment, fulfillment, renewal, refunds, receipts and reconciliation.
5. Implement decisions, commands, passage evidence, matching and corrections.
6. Implement notifications, support, reports, health, audit and retention/export.
7. Test isolation, concurrency, failure recovery and boundaries; measure latency.
8. Prepare containers, CI, Helm, AWS Terraform, runbooks and run demonstration.

## Verification
Application compiled and started on Java 21. V1/V2 migrations applied to clean PostgreSQL 17.6. Keycloak 26.7.4 realm starts and issues validated tokens. Initial suite: 25 of 26 tests passed; notification test stub setup is being corrected. Demo found and fixed development AES key length and an unnecessary immutable-quote row lock. Full demo rerun and final suite pending. Official compatibility and provider references recorded in `dependency-inventory.md`.

## External blockers
- Docker blocker resolved; PostgreSQL and Keycloak are running locally.
- Razorpay sandbox credentials and merchant enrollment absent: implement and contract-test adapter; do not claim sandbox verification.
- AWS account, region, credentials, concrete cost approval absent: no provisioning.
- Production notification and hardware delivery contracts absent: simulator and explicit adapter boundaries.

## Unconfirmed policies
All defaults in business-policies.md are provisional. Production retention, commercial terms, merchant ownership, refund treatment, approved manual lane process and safety integration require authority approval.

## Exact next steps
Finish hardening tests and demo; validate Terraform/Helm; build/run image; execute reproducible load checks and security scan; finalize evidence and handover.

## Implemented capabilities awaiting final verification
OIDC/membership/facility/ownership authorization and RLS; authority/device onboarding; encrypted single-use contact verification; driver/vehicle review; immutable versions/quotes; durable provider order/capture/fulfillment; manual renewal and suspension; receipts; maker/checker refunds and reconciliation; access/fallback/override; bounded commands; movement/passages/visits/corrections; notifications; support; reports; authorized export and retention controls; jobs and health.
