# Dependency and integration inventory

Verified against official documentation on 2026-09-27:
- [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html): 4.1.1 supports Java 21. Pin Boot 4.1.1 and Java release 21.
- [Spring Cloud compatibility](https://spring.io/projects/spring-cloud/): 2025.1.3 supports Boot 4.1.x (since 2025.1.2). Use circuit-breaker adapter support, no discovery/config server.
- [springdoc](https://springdoc.org/): 3.1.1 for Boot 4, WebFlux integration.
- [Razorpay raw webhook signatures](https://razorpay.com/docs/webhooks/validate-test/): HMAC-SHA256 over exact bytes; event-ID deduplication; ordering not guaranteed.
- [Create order](https://razorpay.com/docs/api/orders/create/), [fetch payment](https://razorpay.com/docs/api/payments/fetch-with-id/), [refund APIs](https://razorpay.com/docs/api/refunds/), [fetch refund](https://razorpay.com/docs/api/refunds/fetch-with-id/). Provider amount is integer currency subunits. No undocumented general write-idempotency contract assumed.
- [Keycloak containers/import](https://www.keycloak.org/server/containers), [local image example](https://www.keycloak.org/getting-started/getting-started-docker): reproducible imported realm, local password grant solely for API demo.

External contracts: merchant-owned Razorpay test credentials/webhook secrets per configured merchant, notification delivery endpoint, physical gate integration and approved operator process. All absent. Simulator is development-only; real adapter existence does not prove account eligibility or sandbox success.
