package com.cabaccess.billing;

import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.UUID;

@Component
public class BillingWorker {
    private final Db db;
    private final TenantTx tx;
    private final Providers providers;
    private final BillingService service;
    private final ObjectMapper json;

    public BillingWorker(Db db, TenantTx tx, Providers providers, BillingService service, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.providers = providers;
        this.service = service;
        this.json = json;
    }

    public void createOrder(UUID t, UUID id) {
        var o = tx.system(t, () -> {
            var row = db.one("select * from purchase_order where id=? for update", id);
            if (Db.str(row, "status").equals("CREATED"))
                db.update("update purchase_order set status='REVIEW' where id=?", id);
            return row;
        });
        if (!Set.of("CREATED", "REVIEW").contains(Db.str(o, "status"))) return;
        var m = providers.merchant(Db.id(o, "merchant_id"));
        var adapter = providers.adapter(m);
        PaymentProvider.ProviderOrder result;
        if (Db.str(o, "status").equals("CREATED"))
            result = adapter.createOrder(m, new PaymentProvider.OrderRequest(id, Db.num(o, "amount_minor"), Db.str(o, "currency")));
        else
            result = adapter.findOrder(m, id).orElseThrow(() -> new Failure(409, "ORDER_OUTCOME_REQUIRES_RECONCILIATION"));
        Failure.require(result.amount() == Db.num(o, "amount_minor") && result.currency().equals(Db.str(o, "currency")), 409, "PROVIDER_ORDER_MISMATCH");
        tx.system(t, () -> {
            db.update("update purchase_order set provider_order_id=?,status='PENDING' where id=? and status='REVIEW'", result.id(), id);
            return true;
        });
    }

    public void verifyPayment(UUID t, UUID event) {
        var e = tx.system(t, () -> db.one("select * from provider_event where id=?", event));
        JsonNode claimed = json.readTree(Db.str(e, "payload")).path("payload").path("payment").path("entity");
        var m = providers.merchant(Db.id(e, "merchant_id"));
        var payment = providers.adapter(m).payment(m, claimed.path("id").asText());
        tx.system(t, () -> {
            service.accept(t, m, payment, claimed);
            return true;
        });
    }

    public void fulfill(UUID t, UUID id) {
        tx.system(t, () -> {
            service.fulfill(t, id);
            return true;
        });
    }

    public void refund(UUID t, UUID id) {
        var r = tx.system(t, () -> {
            var row = db.one("select r.*,p.provider_payment_id,p.merchant_id from refund r join payment_attempt p on p.id=r.payment_id and p.tenant_id=r.tenant_id where r.id=? for update of r", id);
            if (Db.str(row, "status").equals("APPROVED")) db.update("update refund set status='REVIEW' where id=?", id);
            return row;
        });
        if (Set.of("PROCESSED", "FAILED", "REJECTED").contains(Db.str(r, "status"))) return;
        var m = providers.merchant(Db.id(r, "merchant_id"));
        var provider = providers.adapter(m);
        String payment = Db.str(r, "provider_payment_id");
        PaymentProvider.ProviderRefund result;
        if (Db.str(r, "status").equals("APPROVED")) result = provider.refund(m, payment, Db.num(r, "amount_minor"), id);
        else
            result = provider.findRefund(m, payment, id).orElseThrow(() -> new Failure(409, "REFUND_OUTCOME_REQUIRES_RECONCILIATION"));
        tx.system(t, () -> {
            service.confirmRefund(t, id, result);
            return true;
        });
        if (!Set.of("processed", "failed").contains(result.status())) throw new Failure(503, "REFUND_PENDING");
    }

    public void verifyRefund(UUID t, UUID event) {
        var e = tx.system(t, () -> db.one("select * from provider_event where id=?", event));
        JsonNode claimed = json.readTree(Db.str(e, "payload")).path("payload").path("refund").path("entity");
        var m = providers.merchant(Db.id(e, "merchant_id"));
        UUID id = UUID.fromString(claimed.path("receipt").asText());
        var r = tx.system(t, () -> db.one("select r.*,p.merchant_id from refund r join payment_attempt p on p.id=r.payment_id and p.tenant_id=r.tenant_id where r.id=?", id));
        Failure.require(Db.id(r, "merchant_id").equals(m.id()), 409, "REFUND_MERCHANT_MISMATCH");
        refund(t, id);
    }

    public void reconcile(UUID t, UUID id) {
        var o = tx.system(t, () -> db.one("select * from purchase_order where id=?", id));
        if (Set.of("CREATED", "REVIEW").contains(Db.str(o, "status"))) {
            createOrder(t, id);
            o = tx.system(t, () -> db.one("select * from purchase_order where id=?", id));
        }
        var m = providers.merchant(Db.id(o, "merchant_id"));
        var payments = providers.adapter(m).payments(m, Db.str(o, "provider_order_id"));
        for (var p : payments)
            if ("captured".equals(p.status())) tx.system(t, () -> {
                service.accept(t, m, p, null);
                return true;
            });
        tx.system(t, () -> {
            var current = db.one("select * from purchase_order where id=?", id);
            if (Set.of("CONFIRMED", "FULFILLED").contains(Db.str(current, "status")))
                db.update("update reconciliation_finding set status='RESOLVED' where order_id=?", id);
            else service.finding(t, id, "NO_CAPTURE", "Provider has no captured payment; no entitlement granted");
            return true;
        });
        for (var r : tx.system(t, () -> db.list("select id from refund where order_id=? and status in ('REVIEW','PENDING','APPROVED')", id)))
            refund(t, Db.id(r, "id"));
    }
}
