-- Deterministic local provider state is persisted across worker/application restarts.
CREATE TABLE simulator_order
(
    id           text PRIMARY KEY,
    tenant_id    uuid   NOT NULL REFERENCES tenant_directory (id),
    merchant_id  uuid   NOT NULL,
    receipt      uuid   NOT NULL,
    amount_minor bigint NOT NULL,
    currency     text   NOT NULL,
    UNIQUE (merchant_id, receipt),
    FOREIGN KEY (tenant_id, merchant_id) REFERENCES merchant_route (tenant_id, id)
);
CREATE TABLE simulator_payment
(
    id           text PRIMARY KEY,
    tenant_id    uuid   NOT NULL REFERENCES tenant_directory (id),
    order_id     text   NOT NULL REFERENCES simulator_order (id),
    amount_minor bigint NOT NULL,
    currency     text   NOT NULL,
    status       text   NOT NULL
);
CREATE TABLE simulator_refund
(
    id           text PRIMARY KEY,
    tenant_id    uuid   NOT NULL REFERENCES tenant_directory (id),
    payment_id   text   NOT NULL REFERENCES simulator_payment (id),
    amount_minor bigint NOT NULL,
    receipt      uuid   NOT NULL UNIQUE,
    status       text   NOT NULL
);
ALTER TABLE simulator_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE simulator_order FORCE ROW LEVEL SECURITY;
CREATE
POLICY tenant_isolation ON simulator_order USING(tenant_id=nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant',true),'')::uuid);
ALTER TABLE simulator_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE simulator_payment FORCE ROW LEVEL SECURITY;
CREATE
POLICY tenant_isolation ON simulator_payment USING(tenant_id=nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant',true),'')::uuid);
ALTER TABLE simulator_refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE simulator_refund FORCE ROW LEVEL SECURITY;
CREATE
POLICY tenant_isolation ON simulator_refund USING(tenant_id=nullif(current_setting('app.tenant',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant',true),'')::uuid);
GRANT
SELECT,
INSERT
,
UPDATE
ON simulator_order,simulator_payment,simulator_refund TO cab_app;
