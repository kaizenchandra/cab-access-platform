package com.cabaccess.driver;

import com.cabaccess.authority.AuthorityService;
import com.cabaccess.identity.Actor;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.Crypto;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import com.cabaccess.shared.SecretBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DriverService {
    private final Db db;
    private final TenantTx tx;
    private final Clock clock;
    private final AuthorityService authority;
    private final boolean local;
    private final SecretBox secrets;
    private final com.cabaccess.operations.Outbox outbox;

    public DriverService(Db db, TenantTx tx, Clock clock, AuthorityService authority, @Value("${cab.local-delivery}") boolean local, SecretBox secrets, com.cabaccess.operations.Outbox outbox) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.authority = authority;
        this.local = local;
        this.secrets = secrets;
        this.outbox = outbox;
    }

    public Map<String, Object> register(UUID t, Actor a, DriverApi.DriverInput x) {
        return tx.system(t, () -> {
            db.one("select id from authority where tenant_id=?", t);
            Failure.require(db.count("select count(*) from membership where tenant_id=? and subject=?", t, a.subject()) == 0 || tx.driver(t, a), 409, "STAFF_CANNOT_REGISTER_AS_DRIVER");
            authority.membership(t, a.subject(), "DRIVER", null);
            UUID id = UUID.randomUUID();
            db.update("insert into driver_profile(id,tenant_id,subject,name,contact,created_at) values(?,?,?,?,?,?)", id, t, a.subject(), x.name(), x.contact(), clock.instant());
            tx.audit(t, a, "DRIVER_REGISTERED", id, "Self registration");
            return db.one("select * from driver_profile where id=?", id);
        });
    }

    public Map<String, Object> challenge(UUID t, Actor a) {
        var d = db.one("select * from driver_profile where tenant_id=? and subject=? for update", t, a.subject());
        UUID driver = Db.id(d, "id");
        Failure.require(db.count("select count(*) from contact_challenge where driver_id=? and created_at>?", driver, clock.instant().minusSeconds(60)) == 0, 429, "CHALLENGE_RATE_LIMIT");
        UUID id = UUID.randomUUID();
        String code = "%06d".formatted(new SecureRandom().nextInt(1_000_000));
        db.update("update contact_challenge set used_at=? where driver_id=? and used_at is null", clock.instant(), driver);
        db.update("insert into contact_challenge(id,tenant_id,driver_id,code_hash,expires_at,created_at) values(?,?,?,?,?,?)", id, t, driver, Crypto.hash(id + ":" + code), clock.instant().plusSeconds(300), clock.instant());
        // Delivery adapter owns dispatch; local plaintext is permitted only in the explicit local profile.
        db.update("insert into local_delivery(id,tenant_id,subject,challenge_id,code) values(?,?,?,?,?)", UUID.randomUUID(), t, a.subject(), id, secrets.encrypt(code));
        outbox.enqueue(t, "CONTACT", id);
        return Map.of("id", id, "expires_at", clock.instant().plusSeconds(300));
    }

    public Map<String, Object> verify(UUID t, Actor a, UUID id, String code) {
        var c = db.one("select c.* from contact_challenge c join driver_profile d on d.id=c.driver_id and d.tenant_id=c.tenant_id where c.id=? and d.subject=? for update of c", id, a.subject());
        boolean available = c.get("used_at") == null && Db.num(c, "attempts") < 5 && Db.instant(c, "expires_at").isAfter(clock.instant());
        boolean correct = available && Crypto.same(Db.str(c, "code_hash"), Crypto.hash(id + ":" + code));
        if (available)
            db.update("update contact_challenge set attempts=attempts+1,used_at=case when ? then ? else used_at end where id=?", correct, clock.instant(), id);
        if (correct) {
            db.update("update driver_profile set verified=true where id=?", Db.id(c, "driver_id"));
            db.update("delete from local_delivery where challenge_id=?", id);
            tx.audit(t, a, "CONTACT_VERIFIED", Db.id(c, "driver_id"), "Single-use challenge");
        }
        return Map.of("verified", correct, "code", correct ? "VERIFIED" : "INVALID_OR_EXPIRED_CHALLENGE");
    }

    public Map<String, Object> vehicle(UUID t, Actor a, DriverApi.VehicleInput x) {
        var driver = db.one("select * from driver_profile where tenant_id=? and subject=?", t, a.subject());
        Failure.require(Boolean.TRUE.equals(driver.get("verified")), 409, "CONTACT_NOT_VERIFIED");
        UUID id = UUID.randomUUID();
        db.update("insert into vehicle(id,tenant_id,driver_id,plate,vehicle_class) values(?,?,?,?,?)", id, t, Db.id(driver, "id"), x.plate().replace(" ", "").toUpperCase(Locale.ROOT), x.vehicleClass());
        tx.audit(t, a, "VEHICLE_REGISTERED", id, "Self registration");
        return db.one("select * from vehicle where id=?", id);
    }

    public Map<String, Object> ownedVehicle(UUID t, Actor a, UUID id, UUID facility, String... roles) {
        var v = db.one("select v.*,d.subject,d.verified from vehicle v join driver_profile d on d.tenant_id=v.tenant_id and d.id=v.driver_id where v.id=?", id);
        tx.owner(t, a, Db.str(v, "subject"), facility, roles);
        return v;
    }

    public void eligible(UUID t, UUID vehicle, UUID facility) {
        var v = db.one("select v.*,d.verified from vehicle v join driver_profile d on d.id=v.driver_id and d.tenant_id=v.tenant_id where v.id=?", vehicle);
        Failure.require(Boolean.TRUE.equals(v.get("verified")), 409, "CONTACT_NOT_VERIFIED");
        Failure.require(!Boolean.TRUE.equals(v.get("suspended")), 409, "VEHICLE_SUSPENDED");
        Failure.require(db.count("select count(*) from eligibility where tenant_id=? and vehicle_id=? and facility_id=? and status='APPROVED'", t, vehicle, facility) == 1, 409, "VEHICLE_NOT_APPROVED");
    }

    public Map<String, Object> review(UUID t, Actor a, UUID vehicle, UUID facility, DriverApi.ReviewInput x) {
        tx.permit(t, a, facility, "AUTHORITY_ADMIN", "VEHICLE_REVIEWER");
        db.one("select id from vehicle where id=?", vehicle);
        UUID id = UUID.randomUUID();
        db.update("insert into eligibility(id,tenant_id,vehicle_id,facility_id,status,reason,reviewed_by,reviewed_at) values(?,?,?,?,?,?,?,?) on conflict(tenant_id,vehicle_id,facility_id) do update set status=excluded.status,reason=excluded.reason,reviewed_by=excluded.reviewed_by,reviewed_at=excluded.reviewed_at", id, t, vehicle, facility, x.status(), x.reason(), a.subject(), clock.instant());
        db.update("insert into eligibility_history(id,tenant_id,vehicle_id,facility_id,status,reason,actor,created_at) values(?,?,?,?,?,?,?,?)", UUID.randomUUID(), t, vehicle, facility, x.status(), x.reason(), a.subject(), clock.instant());
        tx.audit(t, a, "ELIGIBILITY_REVIEWED", vehicle, x.reason());
        return db.one("select * from eligibility where vehicle_id=? and facility_id=?", vehicle, facility);
    }
}
