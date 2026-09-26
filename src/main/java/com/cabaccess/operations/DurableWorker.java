package com.cabaccess.operations;

import com.cabaccess.access.GateWorker;
import com.cabaccess.billing.BillingWorker;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.movement.MovementService;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class DurableWorker {
    private final Db db;
    private final TenantTx tx;
    private final Clock clock;
    private final BillingWorker billing;
    private final GateWorker gate;
    private final MovementService movement;
    private final NotificationWorker notifications;
    private final Outbox outbox;
    private final boolean enabled;
    private final MeterRegistry metrics;

    public DurableWorker(Db db, TenantTx tx, Clock clock, BillingWorker billing, GateWorker gate, MovementService movement, NotificationWorker notifications, Outbox outbox, @Value("${cab.worker-enabled}") boolean enabled, MeterRegistry metrics) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.billing = billing;
        this.gate = gate;
        this.movement = movement;
        this.notifications = notifications;
        this.outbox = outbox;
        this.enabled = enabled;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${cab.worker-delay-ms:500}")
    public void tick() {
        if (!enabled) return;
        for (var tenant : db.list("select id from tenant_directory where active")) {
            try {
                pump(Db.id(tenant, "id"), 20);
            } catch (RuntimeException e) {
                metrics.counter("cab.worker.tenant_failures").increment();
            }
        }
    }

    public int pump(UUID tenant, int limit) {
        int processed = 0;
        for (int i = 0; i < limit; i++) {
            var job = claim(tenant);
            if (job.isEmpty()) break;
            execute(tenant, job.get());
            processed++;
        }
        return processed;
    }

    public Optional<Map<String, Object>> claim(UUID t) {
        return tx.system(t, () -> {
            var row = db.optional("select * from outbox where (state='READY' and available_at<=?) or (state='LEASED' and lease_until<?) order by case when kind='GATE' then 0 else 1 end,available_at,id for update skip locked limit 1", clock.instant(), clock.instant());
            if (row.isEmpty()) return row;
            UUID token = UUID.randomUUID();
            UUID id = Db.id(row.get(), "id");
            db.update("update outbox set state='LEASED',lease_until=?,lease_token=?,attempts=attempts+1 where id=?", clock.instant().plusSeconds(30), token, id);
            return Optional.of(db.one("select * from outbox where id=?", id));
        });
    }

    public void execute(UUID t, Map<String, Object> job) {
        UUID id = Db.id(job, "id"), resource = Db.id(job, "resource_id"), token = Db.id(job, "lease_token");
        String kind = Db.str(job, "kind");
        try {
            switch (kind) {
                case "CREATE_ORDER" -> billing.createOrder(t, resource);
                case "VERIFY_PAYMENT" -> billing.verifyPayment(t, resource);
                case "FULFILL" -> billing.fulfill(t, resource);
                case "REFUND" -> billing.refund(t, resource);
                case "VERIFY_REFUND" -> billing.verifyRefund(t, resource);
                case "RECONCILE" -> billing.reconcile(t, resource);
                case "GATE" -> gate.dispatch(t, resource);
                case "MATCH_VISITS" -> tx.system(t, () -> {
                    movement.match(t, resource);
                    return true;
                });
                case "NOTIFY" -> notifications.deliver(t, resource);
                case "CONTACT" -> notifications.contact(t, resource);
                default -> throw new Failure(500, "UNKNOWN_JOB_KIND");
            }
            tx.system(t, () -> {
                int updated = db.update("update outbox set state='DONE',lease_until=null,last_error=null where id=? and lease_token=?", id, token);
                if (updated == 1)
                    db.update("insert into inbox(id,tenant_id,consumer,event_id,processed_at) values(?,?,?,?,?) on conflict do nothing", UUID.randomUUID(), t, kind, id, clock.instant());
                return true;
            });
            metrics.counter("cab.worker.completed", "kind", kind).increment();
        } catch (RuntimeException e) {
            String code = e instanceof Failure f ? f.code : "PROCESSING_FAILED";
            tx.system(t, () -> {
                boolean dead = Db.num(job, "attempts") >= 5;
                db.update("update outbox set state=?,available_at=?,lease_until=null,last_error=? where id=? and lease_token=?", dead ? "DEAD" : "READY", clock.instant().plusSeconds(Math.min(60, 1L << Math.min(6, Db.num(job, "attempts")))), code, id, token);
                if (dead && kind.equals("NOTIFY"))
                    db.update("update notification set status='FAILED' where id=? and status<>'DELIVERED'", resource);
                return true;
            });
            metrics.counter("cab.worker.failures", "kind", kind).increment();
        }
    }

    @Scheduled(fixedDelayString = "${cab.maintenance-delay-ms:60000}")
    public void maintain() {
        if (!enabled) return;
        for (var tenant : db.list("select id from tenant_directory where active")) {
            try {
                maintenance(Db.id(tenant, "id"));
            } catch (RuntimeException e) {
                metrics.counter("cab.maintenance.failures").increment();
            }
        }
    }

    public void maintenance(UUID t) {
        tx.system(t, () -> {
            for (var p : db.list("select e.id,e.end_at,o.subject from entitlement_period e join purchase_order o on o.id=e.order_id and o.tenant_id=e.tenant_id where not e.revoked and e.end_at<=?", clock.instant().plusSeconds(3 * 86400))) {
                outbox.notification(t, Db.str(p, "subject"), Db.instant(p, "end_at").isAfter(clock.instant()) ? "EXPIRING" : "EXPIRED", Db.id(p, "id"));
            }
            db.update("update gate_command set status=case when status='PENDING' then 'EXPIRED' else 'UNKNOWN' end where status in ('PENDING','DISPATCHED') and expires_at<=?", clock.instant());
            db.update("delete from local_delivery where challenge_id in(select id from contact_challenge where expires_at<=? or used_at is not null)", clock.instant());
            for (var e : db.list("select p.raw_event_id from visit v join passage_evidence p on p.id=v.entry_passage_id and p.tenant_id=v.tenant_id where v.exit_at is null and v.entry_at<?", clock.instant().minusSeconds(86400)))
                movement.match(t, Db.id(e, "raw_event_id"));
            return true;
        });
    }
}
