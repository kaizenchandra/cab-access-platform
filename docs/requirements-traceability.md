# Requirements and verification plan

The MVP covers one authority tenant per subscription, one vehicle/facility and explicit zones; unlimited eligible visits
only. Driver collections belong to the collecting authority merchant, not SaaS revenue. Demo names, prices, vehicles and
contacts are synthetic.

| ID  | Capability / invariant                                                                              | Verification planned                                 |
|-----|-----------------------------------------------------------------------------------------------------|------------------------------------------------------|
| IAM | OIDC issuer/signature/audience/expiry; membership, ownership, facility roles; time-bound support    | SecurityIntegrationTest; Demo HTTP negative requests |
| TEN | Tenant queries, writes, jobs, reports, export and composite references; restricted-role RLS         | PlatformIntegrationTest isolation                    |
| ONB | Authority/facility/zone/gate/device; verified driver; approved vehicle                              | Demo and PlatformIntegrationTest                     |
| PLN | Immutable version, exact minor units, quote snapshots and calendar validity                         | CalendarPolicyTest; purchase integration             |
| PAY | Trusted merchant, signed raw webhook, final status, mismatch rejection and durable idempotency      | PaymentIntegrationTest; RazorpayContractTest         |
| ENT | Activation recovery, concurrent renewals, no overlaps, suspension and boundaries                    | PaymentIntegrationTest; CalendarPolicyTest           |
| REF | Approval, reserved refund balance, ambiguity recovery, confirmed entitlement treatment              | PaymentIntegrationTest                               |
| ACC | Confidence/fallback, scope, eligibility, validity, restriction, override                            | AccessMovementIntegrationTest                        |
| CMD | Bounded command validity; separate acknowledgement and physical passage; no stale replay            | AccessMovementIntegrationTest                        |
| MOV | Raw events, exact replay, ambiguity, late/out-of-order entry/exit, correction and occupancy quality | AccessMovementIntegrationTest                        |
| OPS | Durable notifications, expiry deduplication, cases, audit, exports, approved retention              | OperationsIntegrationTest                            |
| RES | Outbox lease/retry/restart; integration outage fail closed                                          | Integration suite                                    |
| DEP | Clean migrations, container/Compose, Helm, Terraform, smoke and workload                            | scripts/verify.sh; scripts/demo.py; scripts/load.py  |

Test names and results will be updated to match delivered evidence. Planned verification is not a passing result.

## Deferred

Fleet bulk purchasing/consolidated billing, automatic renewal, bundles/discount charging, cross-authority coverage,
dynamic pricing, loyalty, prediction, advanced fraud, overstay charging and booking integrations. These need separately
approved commercial/integration models. Provider, notification and gate ports and immutable versions provide extension
points without extra services.
