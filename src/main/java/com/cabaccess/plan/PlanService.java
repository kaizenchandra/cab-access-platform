package com.cabaccess.plan;

import com.cabaccess.driver.DriverService;
import com.cabaccess.identity.Actor;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

@Service
public class PlanService {
    private final Db db;
    private final TenantTx tx;
    private final Clock clock;
    private final PlanVersions versions;
    private final DriverService drivers;

    public PlanService(Db db, TenantTx tx, Clock clock, PlanVersions versions, DriverService drivers) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.versions = versions;
        this.drivers = drivers;
    }

    public Map<String, Object> create(UUID t, Actor a, PlanApi.PlanInput x) {
        tx.permit(t, a, x.facilityId(), "AUTHORITY_ADMIN");
        UUID id = UUID.randomUUID();
        db.update("insert into plan(id,tenant_id,facility_id,name) values(?,?,?,?)", id, t, x.facilityId(), x.name());
        tx.audit(t, a, "PLAN_CREATED", id, x.name());
        return db.one("select * from plan where id=?", id);
    }

    public Map<String, Object> publish(UUID t, Actor a, UUID id, PlanApi.VersionInput x) {
        var p = db.one("select * from plan where id=? for update", id);
        UUID f = Db.id(p, "facility_id");
        tx.permit(t, a, f, "AUTHORITY_ADMIN");
        Failure.require(!Boolean.TRUE.equals(p.get("retired")), 409, "PLAN_RETIRED");
        int n = (int) db.count("select count(*) from plan_version where plan_id=?", id) + 1;
        UUID v = UUID.randomUUID();
        versions.saveAndFlush(new PlanVersion(v, t, id, f, n, x, clock.instant()));
        for (UUID z : new HashSet<>(x.zoneIds()))
            db.update("insert into plan_zone(id,tenant_id,plan_version_id,facility_id,zone_id) values(?,?,?,?,?)", UUID.randomUUID(), t, v, f, z);
        tx.audit(t, a, "PLAN_VERSION_PUBLISHED", v, "Version " + n);
        return db.one("select * from plan_version where id=?", v);
    }

    public Map<String, Object> quote(UUID t, Actor a, PlanApi.QuoteInput x) {
        tx.permit(t, a, null, "DRIVER");
        var v = db.one("select v.*,p.retired,f.timezone,f.policy_version,f.enabled from plan_version v join plan p on p.id=v.plan_id and p.tenant_id=v.tenant_id join facility f on f.id=v.facility_id and f.tenant_id=v.tenant_id where v.id=?", x.planVersionId());
        UUID f = Db.id(v, "facility_id");
        Failure.require(!Boolean.TRUE.equals(v.get("retired")) && Boolean.TRUE.equals(v.get("enabled")), 409, "PLAN_UNAVAILABLE");
        var vehicle = drivers.ownedVehicle(t, a, x.vehicleId(), f);
        drivers.eligible(t, x.vehicleId(), f);
        Failure.require(Db.str(v, "vehicle_class").equals(Db.str(vehicle, "vehicle_class")), 409, "VEHICLE_CLASS_RESTRICTED");
        if (x.futureStart() != null)
            Failure.require(!x.futureStart().isBefore(clock.instant()) && !x.futureStart().isAfter(clock.instant().plus(Duration.ofDays(90))), 400, "FUTURE_START_OUT_OF_RANGE");
        List<String> zones = db.list("select zone_id from plan_zone where plan_version_id=? order by zone_id", x.planVersionId()).stream().map(z -> Db.str(z, "zone_id")).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : List.of("duration", "amount_minor", "currency", "coverage", "exclusions", "terms", "vehicle_class", "timezone", "policy_version"))
            snapshot.put(key, v.get(key));
        snapshot.put("zone_ids", zones);
        snapshot.put("plan_version_id", x.planVersionId().toString());
        snapshot.put("charging_model", "UNLIMITED_ELIGIBLE_VISITS");
        UUID id = UUID.randomUUID();
        db.update("insert into purchase_quote(id,tenant_id,vehicle_id,facility_id,plan_version_id,subject,amount_minor,currency,snapshot,future_start,expires_at,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)", id, t, x.vehicleId(), f, x.planVersionId(), a.subject(), Db.num(v, "amount_minor"), Db.str(v, "currency"), db.json(snapshot), x.futureStart(), clock.instant().plusSeconds(900), clock.instant());
        return db.one("select * from purchase_quote where id=?", id);
    }
}
