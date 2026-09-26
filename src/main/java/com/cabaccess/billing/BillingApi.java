package com.cabaccess.billing;

import com.cabaccess.identity.Actor;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.operations.Outbox;
import com.cabaccess.shared.Crypto;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import com.cabaccess.shared.Idempotency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BillingApi {
    private final BillingService service;
    private final TenantTx tx;
    private final Db db;
    private final Outbox outbox;
    private final Providers providers;
    private final Idempotency idem;

    public BillingApi(BillingService service, TenantTx tx, Db db, Outbox outbox, Providers providers, Idempotency idem) {
        this.service = service;
        this.tx = tx;
        this.db = db;
        this.outbox = outbox;
        this.providers = providers;
        this.idem = idem;
    }

    @PostMapping("/tenants/{t}/orders")
    Mono<Map<String, Object>> purchase(@PathVariable UUID t, @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PurchaseInput x) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.purchase(t, a, x.quoteId(), key));
    }

    @GetMapping("/tenants/{t}/orders/{id}")
    Mono<Map<String, Object>> order(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> {
            var o = new LinkedHashMap<>(service.order(t, a, id));
            o.put("payments", db.list("select * from payment_attempt where order_id=? order by accepted_at", id));
            o.put("entitlements", db.list("select * from entitlement_period where order_id=?", id));
            return o;
        });
    }

    @GetMapping("/tenants/{t}/orders/{id}/receipt")
    Mono<ResponseEntity<String>> receipt(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=receipt-" + id + ".txt").body(service.receipt(t, a, id)));
    }

    @PostMapping("/provider/{merchant}/webhook")
    Mono<Map<String, Object>> webhook(@PathVariable UUID merchant, @RequestHeader("X-Razorpay-Event-Id") String event, @RequestHeader("X-Razorpay-Signature") String signature, @RequestBody byte[] body) {
        return Mono.fromCallable(() -> service.webhook(merchant, event, signature, body)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/tenants/{t}/local/orders/{id}/capture")
    Mono<Map<String, Object>> simulate(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return Mono.fromCallable(() -> {
            var o = tx.system(t, () -> {
                tx.member(t, a);
                return service.order(t, a, id);
            });
            var merchant = providers.merchant(Db.id(o, "merchant_id"));
            var adapter = providers.adapter(merchant);
            Failure.require(adapter instanceof SimulatorProvider, 404, "SIMULATOR_DISABLED");
            var p = ((SimulatorProvider) adapter).capture(merchant, Db.str(o, "provider_order_id"));
            byte[] body = db.json(Map.of("event", "payment.captured", "payload", Map.of("payment", Map.of("entity", Map.of("id", p.id(), "order_id", p.orderId(), "amount", p.amount(), "currency", p.currency(), "status", p.status()))))).getBytes(StandardCharsets.UTF_8);
            return service.webhook(merchant.id(), "sim_event_" + p.id(), Crypto.hmac(body, adapter.webhookSecret(merchant)), body);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/tenants/{t}/subscriptions/{id}")
    Mono<Map<String, Object>> subscription(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.subscription(t, a, id));
    }

    @PostMapping("/tenants/{t}/subscriptions/{id}/renewals")
    Mono<Map<String, Object>> renewal(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody PurchaseInput x) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> {
            var s = service.subscription(t, a, id);
            var q = db.one("select * from purchase_quote where id=?", x.quoteId());
            Failure.require(Db.id(s, "vehicle_id").equals(Db.id(q, "vehicle_id")) && Db.id(s, "facility_id").equals(Db.id(q, "facility_id")), 409, "RENEWAL_TARGET_MISMATCH");
            return service.purchase(t, a, x.quoteId(), key);
        });
    }

    @PostMapping("/tenants/{t}/subscriptions/{id}/suspension")
    Mono<Map<String, Object>> suspend(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SuspensionInput x) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> {
            var s = db.one("select * from subscription where id=? for update", id);
            tx.permit(t, a, Db.id(s, "facility_id"), "AUTHORITY_ADMIN", "GATE_SUPERVISOR");
            db.update("update subscription set suspended=?,suspension_reason=?,version=version+1 where id=?", x.suspended(), x.reason(), id);
            tx.audit(t, a, x.suspended() ? "SUBSCRIPTION_SUSPENDED" : "SUBSCRIPTION_RESUMED", id, x.reason());
            return service.subscription(t, a, id);
        });
    }

    @PostMapping("/tenants/{t}/refunds")
    Mono<Map<String, Object>> refund(@PathVariable UUID t, @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody RefundInput x) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.refund(t, a, x.paymentId(), x.amountMinor(), x.reason(), key));
    }

    @PostMapping("/tenants/{t}/refunds/{id}/approve")
    Mono<Map<String, Object>> approve(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.approveRefund(t, a, id));
    }

    @GetMapping("/tenants/{t}/refunds/{id}")
    Mono<Map<String, Object>> refund(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> {
            var r = db.one("select * from refund where id=?", id);
            service.order(t, a, Db.id(r, "order_id"));
            return r;
        });
    }

    @PostMapping("/tenants/{t}/orders/{id}/reconcile")
    Mono<Map<String, Object>> reconcile(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> {
            var o = service.order(t, a, id);
            tx.permit(t, a, Db.id(o, "facility_id"), "FINANCE_OFFICER");
            return idem.execute(t, a.subject(), "RECONCILE", key, Map.of("order", id), () -> {
                outbox.enqueue(t, "RECONCILE", id);
                db.update("update outbox set state='READY',attempts=0,available_at=now() where kind='RECONCILE' and resource_id=? and state in ('DONE','DEAD')", id);
                tx.audit(t, a, "RECONCILIATION_REQUESTED", id, "Provider state refresh");
                return Map.of("status", "QUEUED");
            });
        });
    }

    public record PurchaseInput(@NotNull UUID quoteId) {
    }

    public record SuspensionInput(boolean suspended, @NotBlank @Size(max = 500) String reason) {
    }

    public record RefundInput(@NotNull UUID paymentId, @Positive long amountMinor,
                              @NotBlank @Size(max = 500) String reason) {
    }
}
