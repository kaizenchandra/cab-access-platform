package com.cabaccess.operations;

import com.cabaccess.billing.BillingService;
import com.cabaccess.identity.Actor;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OperationsService {
    private final Db db;
    private final TenantTx tx;
    private final Clock clock;
    private final BillingService billing;

    public OperationsService(Db db, TenantTx tx, Clock clock, BillingService billing) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.billing = billing;
    }

    public Map<String, Object> financial(UUID t, Actor a, UUID facility, Instant from, Instant to) {
        tx.permit(t, a, facility, "AUTHORITY_ADMIN", "FINANCE_OFFICER");
        range(from, to);
        long gross = db.count("select coalesce(sum(p.amount_minor),0) from payment_attempt p join purchase_order o on o.id=p.order_id and o.tenant_id=p.tenant_id where o.facility_id=? and p.status='CAPTURED' and p.accepted_at>=? and p.accepted_at<?", facility, from, to);
        long refunds = db.count("select coalesce(sum(r.amount_minor),0) from refund r join purchase_order o on o.id=r.order_id and o.tenant_id=r.tenant_id where o.facility_id=? and r.status='PROCESSED' and r.completed_at>=? and r.completed_at<?", facility, from, to);
        long unresolved = db.count("select count(*) from reconciliation_finding r join purchase_order o on o.id=r.order_id and o.tenant_id=r.tenant_id where o.facility_id=? and r.status='OPEN'", facility);
        return Map.of("currency", "INR", "gross_collections_minor", gross, "refunds_minor", refunds, "net_collections_minor", gross - refunds, "open_reconciliation_findings", unresolved, "accounting_basis", "COLLECTIONS_NOT_REVENUE", "settlement_scope", "MERCHANT_LEVEL_SEPARATE_REPORT");
    }

    private void range(Instant from, Instant to) {
        Failure.require(to.isAfter(from) && Duration.between(from, to).toDays() <= 366, 400, "REPORT_RANGE_MAX_366_DAYS");
    }

    public Map<String, Object> operational(UUID t, Actor a, UUID facility) {
        tx.permit(t, a, facility, "AUTHORITY_ADMIN", "GATE_OPERATOR", "GATE_SUPERVISOR", "SUPPORT_AGENT");
        long open = db.count("select count(*) from visit v where facility_id=? and exit_at is null and not exists(select 1 from visit_correction c where c.visit_id=v.id)", facility);
        long exceptions = db.count("select count(*) from movement_exception where facility_id=? and status='OPEN'", facility);
        long missing = db.count("select count(*) from visit v where facility_id=? and quality<>'MATCHED' and not exists(select 1 from visit_correction c where c.visit_id=v.id)", facility);
        return Map.of("estimated_occupancy", open, "is_estimate", true, "open_exceptions", exceptions, "unmatched_or_ambiguous_visits", missing, "data_quality", exceptions == 0 && missing == 0 ? "NO_KNOWN_GAPS" : "INCOMPLETE", "physical_lane_status", "UNKNOWN");
    }

    public Map<String, Object> support(UUID t, Actor a, OperationsApi.CaseInput x) {
        String owner = a.subject();
        if (x.paymentId() != null) {
            var payment = db.one("select * from payment_attempt where id=?", x.paymentId());
            var order = billing.order(t, a, Db.id(payment, "order_id"));
            Failure.require(Db.id(order, "facility_id").equals(x.facilityId()), 409, "CASE_FACILITY_MISMATCH");
            owner = Db.str(order, "subject");
        }
        if (x.visitId() != null) {
            var visit = db.one("select v.*,d.subject from visit v join vehicle vh on vh.id=v.vehicle_id and vh.tenant_id=v.tenant_id join driver_profile d on d.id=vh.driver_id and d.tenant_id=vh.tenant_id where v.id=?", x.visitId());
            tx.owner(t, a, Db.str(visit, "subject"), x.facilityId(), "SUPPORT_AGENT", "AUTHORITY_ADMIN");
            Failure.require(Db.id(visit, "facility_id").equals(x.facilityId()) && (x.paymentId() == null || owner.equals(Db.str(visit, "subject"))), 409, "CASE_RESOURCE_MISMATCH");
            owner = Db.str(visit, "subject");
        }
        if (!tx.driver(t, a)) tx.permit(t, a, x.facilityId(), "SUPPORT_AGENT", "AUTHORITY_ADMIN");
        UUID id = UUID.randomUUID();
        db.update("insert into support_case(id,tenant_id,subject,facility_id,payment_id,visit_id,description,created_at) values(?,?,?,?,?,?,?,?)", id, t, owner, x.facilityId(), x.paymentId(), x.visitId(), x.description(), clock.instant());
        tx.audit(t, a, "SUPPORT_CASE_CREATED", id, "Linked case");
        return db.one("select * from support_case where id=?", id);
    }

    public List<Map<String, Object>> export(UUID t, Actor a, UUID facility, String kind, int page) {
        Failure.require(page >= 0, 400, "INVALID_PAGE");
        List<Map<String, Object>> rows;
        switch (kind) {
            case "visits" -> {
                tx.permit(t, a, facility, "AUTHORITY_ADMIN", "GATE_SUPERVISOR");
                rows = db.list("select v.* from visit v where facility_id=? order by id limit 1000 offset ?", facility, page * 1000);
            }
            case "payments" -> {
                tx.permit(t, a, facility, "AUTHORITY_ADMIN", "FINANCE_OFFICER");
                rows = db.list("select p.*,o.beneficiary,o.facility_id from payment_attempt p join purchase_order o on o.id=p.order_id and o.tenant_id=p.tenant_id where o.facility_id=? order by p.id limit 1000 offset ?", facility, page * 1000);
            }
            case "audit" -> {
                tx.permit(t, a, null, "AUTHORITY_ADMIN");
                rows = db.list("select * from audit_entry order by id limit 1000 offset ?", page * 1000);
            }
            default -> throw new Failure(400, "UNSUPPORTED_EXPORT");
        }
        tx.audit(t, a, "EXPORT_CREATED", facility, kind + " page " + page);
        return rows;
    }
}
