-- Migration runs as cab_owner. Runtime cab_app must not own tables or bypass RLS.
CREATE TABLE tenant_directory (id uuid PRIMARY KEY, active boolean NOT NULL DEFAULT true);
CREATE TABLE identity_account (subject text PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE merchant_route (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), provider text NOT NULL CHECK(provider IN ('simulator','razorpay')), credential_prefix text NOT NULL, UNIQUE(tenant_id,id));
CREATE TABLE device_route (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), secret_hash text NOT NULL, active boolean NOT NULL DEFAULT true);

CREATE TABLE authority (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), name text NOT NULL, legal_name text NOT NULL, contact text NOT NULL, merchant_id uuid NOT NULL, created_at timestamptz NOT NULL, FOREIGN KEY(tenant_id,merchant_id) REFERENCES merchant_route(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE authority ENABLE ROW LEVEL SECURITY;
ALTER TABLE authority FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON authority USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE facility (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), name text NOT NULL, timezone text NOT NULL, policy_version text NOT NULL DEFAULT 'development-v1', enabled boolean NOT NULL DEFAULT true, UNIQUE(tenant_id,id));

ALTER TABLE facility ENABLE ROW LEVEL SECURITY;
ALTER TABLE facility FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON facility USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE zone (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), facility_id uuid NOT NULL, name text NOT NULL, enabled boolean NOT NULL DEFAULT true, UNIQUE(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE zone ENABLE ROW LEVEL SECURITY;
ALTER TABLE zone FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON zone USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE gate (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), facility_id uuid NOT NULL, zone_id uuid NOT NULL, name text NOT NULL, direction text NOT NULL CHECK(direction IN ('ENTRY','EXIT')), UNIQUE(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,facility_id,zone_id) REFERENCES zone(tenant_id,facility_id,id), UNIQUE(tenant_id,id));

ALTER TABLE gate ENABLE ROW LEVEL SECURITY;
ALTER TABLE gate FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON gate USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE device (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), facility_id uuid NOT NULL, gate_id uuid NOT NULL, name text NOT NULL, active boolean NOT NULL DEFAULT true, last_heartbeat timestamptz, UNIQUE(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,facility_id,gate_id) REFERENCES gate(tenant_id,facility_id,id), FOREIGN KEY(id) REFERENCES device_route(id), UNIQUE(tenant_id,id));

ALTER TABLE device ENABLE ROW LEVEL SECURITY;
ALTER TABLE device FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE membership (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subject text NOT NULL REFERENCES identity_account(subject), role text NOT NULL CHECK(role IN ('AUTHORITY_ADMIN','VEHICLE_REVIEWER','GATE_OPERATOR','GATE_SUPERVISOR','FINANCE_OFFICER','DRIVER','SUPPORT_AGENT')), facility_id uuid, active boolean NOT NULL DEFAULT true, UNIQUE(tenant_id,subject), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON membership USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE support_grant (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subject text NOT NULL, expires_at timestamptz NOT NULL, granted_by text NOT NULL, reason text NOT NULL, revoked_at timestamptz, UNIQUE(tenant_id,id));

ALTER TABLE support_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON support_grant USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE driver_profile (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subject text NOT NULL REFERENCES identity_account(subject), name text NOT NULL, contact text NOT NULL, verified boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL, UNIQUE(tenant_id,subject), UNIQUE(tenant_id,id));

ALTER TABLE driver_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE driver_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON driver_profile USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE contact_challenge (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), driver_id uuid NOT NULL, code_hash text NOT NULL, expires_at timestamptz NOT NULL, attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5), used_at timestamptz, created_at timestamptz NOT NULL, FOREIGN KEY(tenant_id,driver_id) REFERENCES driver_profile(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE contact_challenge ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_challenge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contact_challenge USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE local_delivery (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subject text NOT NULL, challenge_id uuid NOT NULL, code text NOT NULL, FOREIGN KEY(tenant_id,challenge_id) REFERENCES contact_challenge(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE local_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE local_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON local_delivery USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE vehicle (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), driver_id uuid NOT NULL, plate text NOT NULL, vehicle_class text NOT NULL, suspended boolean NOT NULL DEFAULT false, suspension_reason text, version bigint NOT NULL DEFAULT 0, UNIQUE(tenant_id,plate), FOREIGN KEY(tenant_id,driver_id) REFERENCES driver_profile(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE vehicle ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vehicle USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE eligibility (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), vehicle_id uuid NOT NULL, facility_id uuid NOT NULL, status text NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED')), reason text NOT NULL, reviewed_by text NOT NULL, reviewed_at timestamptz NOT NULL, UNIQUE(tenant_id,vehicle_id,facility_id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE eligibility ENABLE ROW LEVEL SECURITY;
ALTER TABLE eligibility FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON eligibility USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE eligibility_history (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), vehicle_id uuid NOT NULL, facility_id uuid NOT NULL, status text NOT NULL, reason text NOT NULL, actor text NOT NULL, created_at timestamptz NOT NULL, FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE eligibility_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE eligibility_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON eligibility_history USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE plan (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), facility_id uuid NOT NULL, name text NOT NULL, retired boolean NOT NULL DEFAULT false, UNIQUE(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plan USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE plan_version (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), plan_id uuid NOT NULL, facility_id uuid NOT NULL, version_number integer NOT NULL, duration text NOT NULL CHECK(duration IN ('WEEKLY','MONTHLY','QUARTERLY','YEARLY')), amount_minor bigint NOT NULL CHECK(amount_minor>0), currency text NOT NULL CHECK(currency='INR'), coverage text NOT NULL, exclusions text NOT NULL, terms text NOT NULL, vehicle_class text NOT NULL, published_at timestamptz NOT NULL, UNIQUE(tenant_id,plan_id,version_number), UNIQUE(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,facility_id,plan_id) REFERENCES plan(tenant_id,facility_id,id), UNIQUE(tenant_id,id));

ALTER TABLE plan_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plan_version USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE plan_zone (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), plan_version_id uuid NOT NULL, facility_id uuid NOT NULL, zone_id uuid NOT NULL, UNIQUE(tenant_id,plan_version_id,zone_id), FOREIGN KEY(tenant_id,facility_id,plan_version_id) REFERENCES plan_version(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,facility_id,zone_id) REFERENCES zone(tenant_id,facility_id,id), UNIQUE(tenant_id,id));

ALTER TABLE plan_zone ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_zone FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plan_zone USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE purchase_quote (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), vehicle_id uuid NOT NULL, facility_id uuid NOT NULL, plan_version_id uuid NOT NULL, subject text NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0), currency text NOT NULL, snapshot text NOT NULL, future_start timestamptz, expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL, FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id,plan_version_id) REFERENCES plan_version(tenant_id,facility_id,id), UNIQUE(tenant_id,id));

ALTER TABLE purchase_quote ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_quote FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON purchase_quote USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE purchase_order (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), quote_id uuid NOT NULL, vehicle_id uuid NOT NULL, facility_id uuid NOT NULL, subject text NOT NULL, merchant_id uuid NOT NULL, beneficiary text NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0), currency text NOT NULL, snapshot text NOT NULL, future_start timestamptz, status text NOT NULL CHECK(status IN ('CREATED','PENDING','CONFIRMED','FULFILLED','REVIEW')), provider_order_id text, idempotency_key text NOT NULL, request_hash text NOT NULL, created_at timestamptz NOT NULL, confirmed_at timestamptz, UNIQUE(tenant_id,subject,idempotency_key), UNIQUE(tenant_id,quote_id), UNIQUE(merchant_id,provider_order_id), FOREIGN KEY(tenant_id,quote_id) REFERENCES purchase_quote(tenant_id,id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), FOREIGN KEY(tenant_id,merchant_id) REFERENCES merchant_route(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE purchase_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON purchase_order USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE payment_attempt (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), order_id uuid NOT NULL, merchant_id uuid NOT NULL, provider_payment_id text NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0), currency text NOT NULL, status text NOT NULL CHECK(status IN ('CAPTURED','FAILED','AUTHORIZED')), accepted_at timestamptz NOT NULL, UNIQUE(merchant_id,provider_payment_id), FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_order(tenant_id,id), FOREIGN KEY(tenant_id,merchant_id) REFERENCES merchant_route(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE payment_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_attempt USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE provider_event (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), merchant_id uuid NOT NULL, source_id text NOT NULL, payload_hash text NOT NULL, payload text NOT NULL, event_type text NOT NULL, received_at timestamptz NOT NULL, UNIQUE(merchant_id,source_id), FOREIGN KEY(tenant_id,merchant_id) REFERENCES merchant_route(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE provider_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE provider_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provider_event USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE subscription (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), vehicle_id uuid NOT NULL, facility_id uuid NOT NULL, suspended boolean NOT NULL DEFAULT false, suspension_reason text, version bigint NOT NULL DEFAULT 0, UNIQUE(tenant_id,vehicle_id,facility_id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON subscription USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE entitlement_period (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subscription_id uuid NOT NULL, order_id uuid NOT NULL, start_at timestamptz NOT NULL, end_at timestamptz NOT NULL, snapshot text NOT NULL, revoked boolean NOT NULL DEFAULT false, CHECK(end_at>start_at), UNIQUE(tenant_id,order_id), FOREIGN KEY(tenant_id,subscription_id) REFERENCES subscription(tenant_id,id), FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_order(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE entitlement_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE entitlement_period FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON entitlement_period USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE refund (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), payment_id uuid NOT NULL, order_id uuid NOT NULL, amount_minor bigint NOT NULL CHECK(amount_minor>0), status text NOT NULL CHECK(status IN ('REQUESTED','APPROVED','PENDING','REVIEW','PROCESSED','FAILED','REJECTED')), reason text NOT NULL, requested_by text NOT NULL, approved_by text, treatment text NOT NULL DEFAULT 'SUSPEND_SUBSCRIPTION', provider_refund_id text, idempotency_key text NOT NULL, request_hash text NOT NULL, created_at timestamptz NOT NULL, completed_at timestamptz, UNIQUE(tenant_id,requested_by,idempotency_key), FOREIGN KEY(tenant_id,payment_id) REFERENCES payment_attempt(tenant_id,id), FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_order(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE refund FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refund USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE reconciliation_finding (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), order_id uuid NOT NULL, kind text NOT NULL, detail text NOT NULL, status text NOT NULL DEFAULT 'OPEN', created_at timestamptz NOT NULL, FOREIGN KEY(tenant_id,order_id) REFERENCES purchase_order(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE reconciliation_finding ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_finding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reconciliation_finding USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE settlement_information (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), merchant_id uuid NOT NULL, provider_reference text NOT NULL, amount_minor bigint NOT NULL, currency text NOT NULL, settled_at timestamptz NOT NULL, source text NOT NULL, UNIQUE(tenant_id,provider_reference), FOREIGN KEY(tenant_id,merchant_id) REFERENCES merchant_route(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE settlement_information ENABLE ROW LEVEL SECURITY;
ALTER TABLE settlement_information FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON settlement_information USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE access_attempt (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), facility_id uuid NOT NULL, gate_id uuid NOT NULL, vehicle_id uuid, source text NOT NULL, evidence text NOT NULL, confidence numeric(4,3) NOT NULL CHECK(confidence BETWEEN 0 AND 1), created_at timestamptz NOT NULL, FOREIGN KEY(tenant_id,facility_id,gate_id) REFERENCES gate(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE access_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access_attempt USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE access_decision (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), attempt_id uuid NOT NULL, outcome text NOT NULL CHECK(outcome IN ('ALLOW','DENY','MANUAL_REVIEW')), reason text NOT NULL, entitlement_id uuid, policy_version text NOT NULL, decision_at timestamptz NOT NULL, next_action text NOT NULL, UNIQUE(tenant_id,attempt_id), FOREIGN KEY(tenant_id,attempt_id) REFERENCES access_attempt(tenant_id,id), FOREIGN KEY(tenant_id,entitlement_id) REFERENCES entitlement_period(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE access_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access_decision USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE access_override (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), decision_id uuid NOT NULL, reason text NOT NULL, evidence text NOT NULL, actor text NOT NULL, created_at timestamptz NOT NULL, UNIQUE(tenant_id,decision_id), FOREIGN KEY(tenant_id,decision_id) REFERENCES access_decision(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE access_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_override FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON access_override USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE gate_command (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), decision_id uuid NOT NULL, gate_id uuid NOT NULL, status text NOT NULL CHECK(status IN ('PENDING','DISPATCHED','ACKNOWLEDGED','FAILED','EXPIRED','UNKNOWN')), expires_at timestamptz NOT NULL, claimed_at timestamptz, acknowledged_at timestamptz, acknowledgement text, UNIQUE(tenant_id,decision_id), FOREIGN KEY(tenant_id,decision_id) REFERENCES access_decision(tenant_id,id), FOREIGN KEY(tenant_id,gate_id) REFERENCES gate(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE gate_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE gate_command FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON gate_command USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE raw_movement_event (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), device_id uuid NOT NULL, facility_id uuid NOT NULL, gate_id uuid NOT NULL, source_id text NOT NULL, payload_hash text NOT NULL, kind text NOT NULL CHECK(kind IN ('OBSERVATION','PASSAGE')), event_at timestamptz NOT NULL, received_at timestamptz NOT NULL, vehicle_id uuid, plate text, direction text NOT NULL CHECK(direction IN ('ENTRY','EXIT')), buffered boolean NOT NULL, passage_key text, decision_id uuid, UNIQUE(tenant_id,device_id,source_id), FOREIGN KEY(tenant_id,facility_id,device_id) REFERENCES device(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,facility_id,gate_id) REFERENCES gate(tenant_id,facility_id,id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,decision_id) REFERENCES access_decision(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE raw_movement_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE raw_movement_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON raw_movement_event USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE passage_evidence (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), raw_event_id uuid NOT NULL, vehicle_id uuid NOT NULL, facility_id uuid NOT NULL, gate_id uuid NOT NULL, passage_key text NOT NULL, direction text NOT NULL, event_at timestamptz NOT NULL, UNIQUE(tenant_id,gate_id,passage_key), UNIQUE(tenant_id,raw_event_id), FOREIGN KEY(tenant_id,raw_event_id) REFERENCES raw_movement_event(tenant_id,id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id,gate_id) REFERENCES gate(tenant_id,facility_id,id), UNIQUE(tenant_id,id));

ALTER TABLE passage_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE passage_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON passage_evidence USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE visit (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), vehicle_id uuid NOT NULL, facility_id uuid NOT NULL, entry_passage_id uuid NOT NULL, exit_passage_id uuid, entry_at timestamptz NOT NULL, exit_at timestamptz, quality text NOT NULL, UNIQUE(tenant_id,entry_passage_id), UNIQUE(tenant_id,exit_passage_id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), FOREIGN KEY(tenant_id,entry_passage_id) REFERENCES passage_evidence(tenant_id,id), FOREIGN KEY(tenant_id,exit_passage_id) REFERENCES passage_evidence(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE visit ENABLE ROW LEVEL SECURITY;
ALTER TABLE visit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON visit USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE movement_exception (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), raw_event_id uuid, vehicle_id uuid, facility_id uuid NOT NULL, reason text NOT NULL, status text NOT NULL DEFAULT 'OPEN', created_at timestamptz NOT NULL, UNIQUE(tenant_id,raw_event_id,reason), FOREIGN KEY(tenant_id,raw_event_id) REFERENCES raw_movement_event(tenant_id,id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE movement_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE movement_exception FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON movement_exception USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE visit_correction (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), visit_id uuid NOT NULL, corrected_exit_at timestamptz NOT NULL, actor text NOT NULL, reason text NOT NULL, created_at timestamptz NOT NULL, FOREIGN KEY(tenant_id,visit_id) REFERENCES visit(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE visit_correction ENABLE ROW LEVEL SECURITY;
ALTER TABLE visit_correction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON visit_correction USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE notification (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subject text NOT NULL, kind text NOT NULL, resource_id uuid NOT NULL, status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DELIVERED','FAILED')), delivery_reference text, created_at timestamptz NOT NULL, delivered_at timestamptz, UNIQUE(tenant_id,kind,resource_id), UNIQUE(tenant_id,id));

ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE support_case (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subject text NOT NULL, facility_id uuid NOT NULL, payment_id uuid, visit_id uuid, description text NOT NULL, status text NOT NULL DEFAULT 'OPEN', resolution text, created_at timestamptz NOT NULL, CHECK(payment_id IS NOT NULL OR visit_id IS NOT NULL), FOREIGN KEY(tenant_id,facility_id) REFERENCES facility(tenant_id,id), FOREIGN KEY(tenant_id,payment_id) REFERENCES payment_attempt(tenant_id,id), FOREIGN KEY(tenant_id,visit_id) REFERENCES visit(tenant_id,id), UNIQUE(tenant_id,id));

ALTER TABLE support_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_case FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON support_case USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE audit_entry (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), actor text NOT NULL, action text NOT NULL, resource_id uuid NOT NULL, reason text NOT NULL, created_at timestamptz NOT NULL, UNIQUE(tenant_id,id));

ALTER TABLE audit_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_entry USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE outbox (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), kind text NOT NULL, resource_id uuid NOT NULL, state text NOT NULL DEFAULT 'READY' CHECK(state IN ('READY','LEASED','DONE','DEAD')), attempts integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL, lease_until timestamptz, lease_token uuid, last_error text, UNIQUE(tenant_id,kind,resource_id), UNIQUE(tenant_id,id));

ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbox USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE inbox (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), consumer text NOT NULL, event_id uuid NOT NULL, processed_at timestamptz NOT NULL, UNIQUE(tenant_id,consumer,event_id), UNIQUE(tenant_id,id));

ALTER TABLE inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inbox USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE retention_policy (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), raw_event_days integer NOT NULL CHECK(raw_event_days>=30), approved boolean NOT NULL DEFAULT false, approved_by text NOT NULL, reason text NOT NULL, updated_at timestamptz NOT NULL, UNIQUE(tenant_id), UNIQUE(tenant_id,id));

ALTER TABLE retention_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON retention_policy USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE TABLE idempotency_record (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant_directory(id), subject text NOT NULL, operation text NOT NULL, request_key text NOT NULL, payload_hash text NOT NULL, response text NOT NULL, UNIQUE(tenant_id,subject,operation,request_key), UNIQUE(tenant_id,id));

ALTER TABLE idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotency_record USING (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK (tenant_id = nullif(current_setting('app.tenant',true),'')::uuid);

CREATE INDEX outbox_due ON outbox(tenant_id,state,available_at);
CREATE INDEX entitlement_valid ON entitlement_period(tenant_id,subscription_id,start_at,end_at);
CREATE INDEX passage_timeline ON passage_evidence(tenant_id,vehicle_id,facility_id,event_at);
CREATE INDEX audit_timeline ON audit_entry(tenant_id,created_at);
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable record'; END $$;

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON plan_version FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON plan_zone FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON purchase_quote FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON audit_entry FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON visit_correction FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON eligibility_history FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON access_attempt FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON access_decision FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON access_override FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON provider_event FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER immutable_record BEFORE UPDATE OR DELETE ON inbox FOR EACH ROW EXECUTE FUNCTION reject_mutation();

GRANT USAGE ON SCHEMA public TO cab_app;
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO cab_app;
REVOKE UPDATE,DELETE ON tenant_directory,identity_account,merchant_route,device_route FROM cab_app;
REVOKE DELETE ON authority,facility,zone,gate,device,driver_profile,vehicle,purchase_order,payment_attempt,subscription,entitlement_period,refund FROM cab_app;
REVOKE UPDATE,DELETE ON plan_version,plan_zone,purchase_quote,audit_entry,visit_correction,eligibility_history,access_attempt,access_decision,access_override,provider_event,inbox FROM cab_app;
