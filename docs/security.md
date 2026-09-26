# Security and permission model

JWT signature, issuer, audience `cab-api`, expiry and subject are validated by Spring Security's established OIDC resource server. Keycloak realm import is local-only; no application token issuer exists. Local password-grant client and fixed synthetic passwords must never be promoted to production. Production clients use authorization code + PKCE or appropriate device credentials.

| Role | Scope |
|---|---|
| Platform administrator | Onboard tenant and trusted merchant mapping; tenant data only after explicit tenant support grant |
| Authority administrator | Organization, memberships, facilities, plans, reporting, retention approval and tenant audit |
| Vehicle reviewer | Review facility eligibility; no billing approval |
| Gate operator | Scoped plate lookup, evidence-based fallback, decisions and visits |
| Gate supervisor | Scoped override with reason/evidence, suspension, corrections and exception review |
| Finance officer | Scoped collection/refund/reconciliation; maker/checker approvals; unscoped finance role for merchant settlements |
| Driver | Own profile, verified vehicles, quotes/orders/subscriptions/receipt, linked support and notifications |
| Support agent | Scoped support and operational lookup, no overrides or refunds |

Facility scope in membership is enforced in resource services, not inferred from request path. Drivers require ownership. Platform grants expire within eight hours and every use is audited. RLS protects tenant rows even if a repository query omits a tenant predicate; it does not replace resource ownership and role checks. The application role is not table owner, superuser or BYPASSRLS. Migration role is never the runtime role. Privileged routing metadata is intentionally distinct and not exposed as a tenant data API.

## Threats and controls
| Threat | Control / remaining production work |
|---|---|
| Tenant selector or foreign UUID substitution | Membership + resource scope + forced RLS + composite foreign keys; restricted-role tests |
| Forged or replayed payment | HMAC over raw bytes, trusted merchant routing, event digest, provider fetch, amount/currency/order check, durable unique fulfillment |
| Refund overrun or insider action | Locked payment balance reservations, distinct finance maker/checker, immutable audit, provider-confirmed treatment |
| Forged ANPR or stale gate command | Bound random device secret digest, timestamp limits, replay digest, confidence/fallback, 10-second command expiry, no replay after dispatch |
| Operator lookup abuse | Scoped role, audit; lookup alone cannot create allow decision |
| Unbounded history/exfiltration | Capped pagination, bounded financial report range, role/ownership filtered exports, export audit |
| Personal data exposure | No token/credential/body logging; encrypted pending contact code, hashed challenge, attempt/expiry limits; raw plate redaction only under approved retention |
| Integration timeout | Short I/O timeouts, payment circuit breaker, durable retries and reconciliation; offline admission unavailable routes manual |
| Notification failure | Independent outbox; never rolls back entitlement; stable delivery idempotency key |

Production requires TLS ingress/device transport, per-merchant secret rotation, approved notification gateway and hardware protocol validation, IdP hardening/MFA, network-level rate limits/WAF, retention/legal review and an independent security assessment. These are launch dependencies, not claimed certifications.
