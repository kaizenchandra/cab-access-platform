package com.cabaccess.billing;

import com.cabaccess.driver.DriverService;
import com.cabaccess.identity.Actor;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.operations.Outbox;
import com.cabaccess.plan.CalendarPolicy;
import com.cabaccess.shared.Crypto;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import com.cabaccess.shared.Idempotency;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BillingService {
    private final Db db;
    private final TenantTx tx;
    private final Clock clock;
    private final Outbox outbox;
    private final Providers providers;
    private final DriverService drivers;
    private final Idempotency idem;
    private final ObjectMapper json;
    private final FulfillmentGuard guard;

    public BillingService(Db db, TenantTx tx, Clock clock, Outbox outbox, Providers providers, DriverService drivers, Idempotency idem, ObjectMapper json, FulfillmentGuard guard) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.outbox = outbox;
        this.providers = providers;
        this.drivers = drivers;
        this.idem = idem;
        this.json = json;
        this.guard = guard;
    }

    public Map<String, Object> purchase(UUID t, Actor a, UUID quote, String key) {
        tx.permit(t, a, null, "DRIVER");
        return idem.execute(t, a.subject(), "PURCHASE", key, Map.of("quote", quote), () -> {
            var q = db.one("select * from purchase_quote where id=? and subject=?", quote, a.subject());
            Failure.require(Db.instant(q, "expires_at").isAfter(clock.instant()), 409, "QUOTE_EXPIRED");
            drivers.eligible(t, Db.id(q, "vehicle_id"), Db.id(q, "facility_id"));
            var authority = db.one("select * from authority where tenant_id=?", t);
            UUID id = UUID.randomUUID();
            db.update("insert into purchase_order(id,tenant_id,quote_id,vehicle_id,facility_id,subject,merchant_id,beneficiary,amount_minor,currency,snapshot,future_start,status,idempotency_key,request_hash,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,'CREATED',?,?,?)", id, t, quote, Db.id(q, "vehicle_id"), Db.id(q, "facility_id"), a.subject(), Db.id(authority, "merchant_id"), Db.str(authority, "legal_name"), Db.num(q, "amount_minor"), Db.str(q, "currency"), Db.str(q, "snapshot"), q.get("future_start"), key, Crypto.hash(quote.toString()), clock.instant());
            outbox.enqueue(t, "CREATE_ORDER", id);
            tx.audit(t, a, "ORDER_CREATED", id, "Server quote accepted");
            return Map.of("id", id, "status", "CREATED");
        });
    }

    public Map<String, Object> order(UUID t, Actor a, UUID id) {
        var o = db.one("select * from purchase_order where id=?", id);
        tx.owner(t, a, Db.str(o, "subject"), Db.id(o, "facility_id"), "AUTHORITY_ADMIN", "FINANCE_OFFICER", "SUPPORT_AGENT");
        return o;
    }

    public Map<String, Object> webhook(UUID merchantId, String eventId, String signature, byte[] body) {
        Failure.require(body.length <= 65536 && eventId != null && !eventId.isBlank() && eventId.length() <= 200, 400, "INVALID_WEBHOOK");
        var m = providers.merchant(merchantId);
        Failure.require(Crypto.same(Crypto.hmac(body, providers.adapter(m).webhookSecret(m)), signature), 401, "INVALID_SIGNATURE");
        JsonNode parsed = json.readTree(body);
        String type = parsed.path("event").asText();
        String payload = new String(body, StandardCharsets.UTF_8);
        String hash = Crypto.hash(payload);
        return tx.system(m.tenant(), () -> {
            db.one("select pg_advisory_xact_lock(hashtextextended(?,0))", merchantId + ":" + eventId);
            var old = db.optional("select * from provider_event where merchant_id=? and source_id=?", merchantId, eventId);
            if (old.isPresent()) {
                Failure.require(hash.equals(Db.str(old.get(), "payload_hash")), 409, "EVENT_PAYLOAD_MISMATCH");
                return Map.of("id", old.get().get("id"), "duplicate", true);
            }
            UUID id = UUID.randomUUID();
            db.update("insert into provider_event(id,tenant_id,merchant_id,source_id,payload_hash,payload,event_type,received_at) values(?,?,?,?,?,?,?,?)", id, m.tenant(), merchantId, eventId, hash, payload, type, clock.instant());
            if (type.equals("payment.captured")) outbox.enqueue(m.tenant(), "VERIFY_PAYMENT", id);
            if (type.startsWith("refund.")) outbox.enqueue(m.tenant(), "VERIFY_REFUND", id);
            return Map.of("id", id, "duplicate", false);
        });
    }

    public void accept(UUID t, PaymentProvider.Merchant m, PaymentProvider.Payment payment, JsonNode claimed) {
        Failure.require("captured".equals(payment.status()), 409, "PAYMENT_NOT_CAPTURED");
        if (claimed != null)
            Failure.require(payment.id().equals(claimed.path("id").asText()) && payment.orderId().equals(claimed.path("order_id").asText()) && payment.amount() == claimed.path("amount").asLong(-1) && payment.currency().equals(claimed.path("currency").asText()) && "captured".equals(claimed.path("status").asText()), 409, "WEBHOOK_PAYMENT_MISMATCH");
        var o = db.one("select * from purchase_order where merchant_id=? and provider_order_id=? for update", m.id(), payment.orderId());
        UUID order = Db.id(o, "id");
        Failure.require(Db.num(o, "amount_minor") == payment.amount() && Db.str(o, "currency").equals(payment.currency()), 409, "PAYMENT_AMOUNT_CURRENCY_MISMATCH");
        var existing = db.optional("select * from payment_attempt where merchant_id=? and provider_payment_id=?", m.id(), payment.id());
        if (existing.isPresent()) {
            Failure.require(Db.id(existing.get(), "order_id").equals(order), 409, "PAYMENT_ORDER_MISMATCH");
            return;
        }
        UUID p = UUID.randomUUID();
        db.update("insert into payment_attempt(id,tenant_id,order_id,merchant_id,provider_payment_id,amount_minor,currency,status,accepted_at) values(?,?,?,?,?,?,?,'CAPTURED',?)", p, t, order, m.id(), payment.id(), payment.amount(), payment.currency(), clock.instant());
        if (Set.of("CONFIRMED", "FULFILLED").contains(Db.str(o, "status"))) {
            finding(t, order, "EXTRA_CAPTURE", "Additional captured payment requires finance review");
            return;
        }
        db.update("update purchase_order set status='CONFIRMED',confirmed_at=? where id=?", clock.instant(), order);
        outbox.enqueue(t, "FULFILL", order);
        outbox.notification(t, Db.str(o, "subject"), "PAYMENT", p);
        tx.audit(t, new Actor("provider:" + m.id(), false), "PAYMENT_CONFIRMED", p, "Verified provider capture");
    }

    public void fulfill(UUID t, UUID order) {
        var o = db.one("select * from purchase_order where id=? for update", order);
        if (Db.str(o, "status").equals("FULFILLED")) return;
        Failure.require(Db.str(o, "status").equals("CONFIRMED"), 409, "ORDER_NOT_CONFIRMED");
        guard.check(order);
        UUID vehicle = Db.id(o, "vehicle_id"), facility = Db.id(o, "facility_id");
        db.update("insert into subscription(id,tenant_id,vehicle_id,facility_id) values(?,?,?,?) on conflict(tenant_id,vehicle_id,facility_id) do nothing", UUID.randomUUID(), t, vehicle, facility);
        var sub = db.one("select * from subscription where vehicle_id=? and facility_id=? for update", vehicle, facility);
        UUID subscription = Db.id(sub, "id");
        Instant start = Db.instant(o, "confirmed_at");
        if (o.get("future_start") != null && Db.instant(o, "future_start").isAfter(start))
            start = Db.instant(o, "future_start");
        var latest = db.optional("select end_at from entitlement_period where subscription_id=? order by end_at desc limit 1", subscription);
        if (latest.isPresent() && Db.instant(latest.get(), "end_at").isAfter(start))
            start = Db.instant(latest.get(), "end_at");
        Map<String, Object> snapshot = db.parse(Db.str(o, "snapshot"));
        Instant end = CalendarPolicy.end(start, snapshot.get("timezone").toString(), CalendarPolicy.Duration.valueOf(snapshot.get("duration").toString()));
        UUID entitlement = UUID.randomUUID();
        db.update("insert into entitlement_period(id,tenant_id,subscription_id,order_id,start_at,end_at,snapshot) values(?,?,?,?,?,?,?) on conflict(tenant_id,order_id) do nothing", entitlement, t, subscription, order, start, end, Db.str(o, "snapshot"));
        db.update("update purchase_order set status='FULFILLED' where id=?", order);
        db.update("update subscription set version=version+1 where id=?", subscription);
        if (db.count("select count(*) from refund where order_id=? and status='PROCESSED'", order) > 0)
            db.update("update subscription set suspended=true,suspension_reason='CONFIRMED_REFUND' where id=?", subscription);
        outbox.notification(t, Db.str(o, "subject"), "ACTIVATION", entitlement);
        tx.audit(t, new Actor("worker", false), "ENTITLEMENT_CREATED", entitlement, "Order " + order);
    }

    public Map<String, Object> subscription(UUID t, Actor a, UUID id) {
        var s = db.one("select s.*,d.subject from subscription s join vehicle v on v.id=s.vehicle_id and v.tenant_id=s.tenant_id join driver_profile d on d.id=v.driver_id and d.tenant_id=v.tenant_id where s.id=?", id);
        tx.owner(t, a, Db.str(s, "subject"), Db.id(s, "facility_id"), "AUTHORITY_ADMIN", "FINANCE_OFFICER", "SUPPORT_AGENT", "GATE_SUPERVISOR");
        var result = new LinkedHashMap<>(s);
        result.put("periods", db.list("select *,case when revoked then 'REVOKED' when start_at>? then 'SCHEDULED' when end_at<=? then 'EXPIRED' else 'ACTIVE' end as period_status from entitlement_period where subscription_id=? order by start_at", clock.instant(), clock.instant(), id));
        return result;
    }

    public Map<String, Object> refund(UUID t, Actor a, UUID payment, long amount, String reason, String key) {
        var p = db.one("select p.*,o.facility_id from payment_attempt p join purchase_order o on o.id=p.order_id and o.tenant_id=p.tenant_id where p.id=? for update of p", payment);
        tx.permit(t, a, Db.id(p, "facility_id"), "FINANCE_OFFICER");
        return idem.execute(t, a.subject(), "REFUND", key, Map.of("payment", payment, "amount", amount, "reason", reason), () -> {
            long reserved = db.count("select coalesce(sum(amount_minor),0) from refund where payment_id=? and status not in ('FAILED','REJECTED')", payment);
            Failure.require(amount > 0 && amount <= Db.num(p, "amount_minor") - reserved, 409, "REFUND_EXCEEDS_BALANCE");
            UUID id = UUID.randomUUID();
            db.update("insert into refund(id,tenant_id,payment_id,order_id,amount_minor,status,reason,requested_by,idempotency_key,request_hash,created_at) values(?,?,?,?,?,'REQUESTED',?,?,?,?,?)", id, t, payment, Db.id(p, "order_id"), amount, reason, a.subject(), key, Crypto.hash(payment + ":" + amount + ":" + reason), clock.instant());
            tx.audit(t, a, "REFUND_REQUESTED", id, reason);
            return db.one("select * from refund where id=?", id);
        });
    }

    public Map<String, Object> approveRefund(UUID t, Actor a, UUID id) {
        var r = db.one("select r.*,o.facility_id from refund r join purchase_order o on o.id=r.order_id and o.tenant_id=r.tenant_id where r.id=? for update of r", id);
        tx.permit(t, a, Db.id(r, "facility_id"), "FINANCE_OFFICER");
        Failure.require(!Db.str(r, "requested_by").equals(a.subject()), 403, "REFUND_SECOND_APPROVER_REQUIRED");
        if (!Db.str(r, "status").equals("REQUESTED")) {
            Failure.require(Db.str(r, "approved_by").equals(a.subject()), 409, "INVALID_REFUND_TRANSITION");
            return r;
        }
        db.update("update refund set status='APPROVED',approved_by=? where id=?", a.subject(), id);
        outbox.enqueue(t, "REFUND", id);
        tx.audit(t, a, "REFUND_APPROVED", id, Db.str(r, "reason"));
        return db.one("select * from refund where id=?", id);
    }

    public void confirmRefund(UUID t, UUID id, PaymentProvider.ProviderRefund p) {
        var r = db.one("select r.*,pa.provider_payment_id from refund r join payment_attempt pa on pa.id=r.payment_id and pa.tenant_id=r.tenant_id where r.id=? for update of r", id);
        Failure.require(p.paymentId().equals(Db.str(r, "provider_payment_id")) && p.amount() == Db.num(r, "amount_minor") && id.toString().equals(p.receipt()), 409, "REFUND_PROVIDER_MISMATCH");
        if (Db.str(r, "status").equals("PROCESSED")) return;
        Failure.require(Set.of("APPROVED", "PENDING", "REVIEW").contains(Db.str(r, "status")), 409, "INVALID_REFUND_TRANSITION");
        String status = switch (p.status()) {
            case "processed" -> "PROCESSED";
            case "failed" -> "FAILED";
            default -> "PENDING";
        };
        db.update("update refund set status=?,provider_refund_id=?,completed_at=? where id=?", status, p.id(), status.equals("PROCESSED") ? clock.instant() : null, id);
        if (status.equals("PROCESSED")) {
            db.update("update subscription set suspended=true,suspension_reason='CONFIRMED_REFUND',version=version+1 where id in(select subscription_id from entitlement_period where order_id=?)", Db.id(r, "order_id"));
            tx.audit(t, new Actor("provider", false), "REFUND_CONFIRMED", id, "Entitlement treatment: SUSPEND_SUBSCRIPTION");
        }
    }

    public void finding(UUID t, UUID order, String kind, String detail) {
        db.update("insert into reconciliation_finding(id,tenant_id,order_id,kind,detail,created_at) values(?,?,?,?,?,?)", UUID.randomUUID(), t, order, kind, detail, clock.instant());
    }

    public String receipt(UUID t, Actor a, UUID order) {
        var o = order(t, a, order);
        Failure.require(Set.of("CONFIRMED", "FULFILLED").contains(Db.str(o, "status")), 409, "PAYMENT_NOT_CONFIRMED");
        var p = db.one("select * from payment_attempt where order_id=? and status='CAPTURED' order by accepted_at limit 1", order);
        return "PAYMENT RECEIPT — NOT A TAX INVOICE\nCollecting entity: " + Db.str(o, "beneficiary") + "\nTenant: " + t + "\nMerchant: " + o.get("merchant_id") + "\nReceipt: " + p.get("id") + "\nOrder: " + order + "\nProvider payment: " + p.get("provider_payment_id") + "\nAmount: " + java.math.BigDecimal.valueOf(Db.num(p, "amount_minor"), 2).toPlainString() + " " + p.get("currency") + "\nAccepted: " + p.get("accepted_at") + "\nCoverage and exclusions: " + o.get("snapshot") + "\nCollections belong to the named collecting entity.\n";
    }
}
