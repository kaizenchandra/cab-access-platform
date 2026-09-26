# Device and external adapter contracts

Device credential is issued once on registration, stored as a SHA-256 digest, and sent only over TLS as `X-Device-Key`.
Registry binds device to tenant/facility/gate/direction; payload cannot select these. Disabled devices are rejected.
Production transport must be deployed behind TLS and rate limits.

`POST /api/v1/devices/{id}/events`:

```json
{"sourceId":"boot-3-sequence-17","kind":"OBSERVATION","eventAt":"2026-09-26T12:00:00Z","plate":"DEMO1234","confidence":0.99,"buffered":false}
```

`PASSAGE` requires `passageKey`, identifying a physical crossing from the upstream sensor/operator system. An
observation alone never creates a passage. Different observations remain raw records. Exact source replay returns
original result; same source with changed data is 409. Same gate/passage key correlates additional evidence and flags
conflicting identity/time. Unidentified passages become exceptions. Buffered or >30-second-old observation never issues
a command. Direction comes from gate configuration.

Gate port sends an authenticated HTTPS POST with `id`, `tenant`, `gate`, `expiresAt`, and matching `Idempotency-Key`;
expected transport acceptance = HTTP 202. The receiver must enforce command-ID deduplication and expiry itself. No
vendor hardware protocol is assumed. Lost response creates UNKNOWN; operators investigate. Device acknowledgement uses
`/commands/{command}/ack` and `{"status":"OPENED"}` or `FAILED`, before expiry. Separate passage evidence is still
required. Hardware emergency egress and safety interlocks are outside this API's control.

`POST /devices/{id}/heartbeat` records receive time. >2 minutes absent is UNHEALTHY, never a claim that a lane is closed
or safe.

Notification port sends HTTPS JSON `{id,recipient,kind,details}` with bearer authentication and stable
`Idempotency-Key`. The agreed gateway must return HTTP 200 with `deliveryReference` only after confirmed delivery,
deduplicate repeated IDs, and avoid returning credentials or personal data. CONTACT_VERIFICATION includes a short-lived
code; other events contain resource IDs. Local adapter only simulates delivery. No production delivery has been
validated.
