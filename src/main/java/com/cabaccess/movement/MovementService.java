package com.cabaccess.movement;

import com.cabaccess.access.AccessService;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.operations.Outbox;
import com.cabaccess.shared.Crypto;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class MovementService {
    private final Db db;
    private final TenantTx tx;
    private final Clock clock;
    private final AccessService access;
    private final Outbox outbox;

    public MovementService(Db db, TenantTx tx, Clock clock, AccessService access, Outbox outbox) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.access = access;
        this.outbox = outbox;
    }

    public UUID authenticate(UUID device, String secret) {
        var r = db.one("select * from device_route where id=? and active", device);
        Failure.require(Crypto.same(Db.str(r, "secret_hash"), Crypto.hash(secret)), 401, "INVALID_DEVICE_CREDENTIAL");
        return Db.id(r, "tenant_id");
    }

    public Map<String, Object> device(UUID id) {
        return db.one("select d.*,g.direction from device d join gate g on g.id=d.gate_id and g.tenant_id=d.tenant_id where d.id=? and d.active", id);
    }

    public Map<String, Object> ingest(UUID t, UUID device, MovementApi.EventInput x) {
        var d = device(device);
        UUID facility = Db.id(d, "facility_id"), gate = Db.id(d, "gate_id");
        String hash = Crypto.hash(db.json(x));
        db.one("select pg_advisory_xact_lock(hashtextextended(?,0))", device + ":" + x.sourceId());
        var old = db.optional("select * from raw_movement_event where device_id=? and source_id=?", device, x.sourceId());
        if (old.isPresent()) {
            Failure.require(hash.equals(Db.str(old.get(), "payload_hash")), 409, "EVENT_PAYLOAD_MISMATCH");
            return result(old.get());
        }
        Instant now = clock.instant();
        Failure.require(!x.eventAt().isAfter(now.plusSeconds(30)) && !x.eventAt().isBefore(now.minus(Duration.ofDays(7))), 400, "EVENT_TIMESTAMP_OUT_OF_RANGE");
        String plate = x.plate().replace(" ", "").toUpperCase(Locale.ROOT);
        var v = db.optional("select id from vehicle where plate=?", plate);
        UUID vehicle = v.map(r -> Db.id(r, "id")).orElse(null);
        UUID id = UUID.randomUUID();
        boolean buffered = x.buffered() || x.eventAt().isBefore(now.minusSeconds(30));
        db.update("insert into raw_movement_event(id,tenant_id,device_id,facility_id,gate_id,source_id,payload_hash,kind,event_at,received_at,vehicle_id,plate,direction,buffered,passage_key) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, t, device, facility, gate, x.sourceId(), hash, x.kind(), x.eventAt(), now, vehicle, plate, Db.str(d, "direction"), buffered, x.passageKey());
        if (x.kind().equals("OBSERVATION") && !buffered && Db.str(d, "direction").equals("ENTRY")) {
            // Serialize per gate/plate so competing observations cannot both issue opens.
            db.one("select pg_advisory_xact_lock(hashtextextended(?,0))", gate + ":" + plate);
            boolean ambiguous = db.count("select count(*) from raw_movement_event where gate_id=? and plate=? and kind='OBSERVATION' and received_at>=? and id<>?", gate, plate, now.minusSeconds(5), id) > 0;
            var decision = access.decide(t, gate, vehicle, x.confidence(), "DEVICE", id.toString(), ambiguous);
            db.update("update raw_movement_event set decision_id=? where id=?", Db.id(decision, "id"), id);
        }
        if (x.kind().equals("PASSAGE")) {
            Failure.require(x.passageKey() != null && !x.passageKey().isBlank(), 400, "PASSAGE_KEY_REQUIRED");
            if (vehicle == null) exception(t, id, null, facility, "UNIDENTIFIED_PASSAGE");
            else {
                var existing = db.optional("select * from passage_evidence where gate_id=? and passage_key=?", gate, x.passageKey());
                if (existing.isPresent()) {
                    boolean same = Db.id(existing.get(), "vehicle_id").equals(vehicle) && Db.instant(existing.get(), "event_at").equals(x.eventAt());
                    if (!same) exception(t, id, vehicle, facility, "CONFLICTING_PASSAGE_KEY");
                } else {
                    db.update("insert into passage_evidence(id,tenant_id,raw_event_id,vehicle_id,facility_id,gate_id,passage_key,direction,event_at) values(?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), t, id, vehicle, facility, gate, x.passageKey(), Db.str(d, "direction"), x.eventAt());
                    outbox.enqueue(t, "MATCH_VISITS", id);
                }
            }
        }
        return result(db.one("select * from raw_movement_event where id=?", id));
    }

    private Map<String, Object> result(Map<String, Object> r) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", r.get("id"));
        result.put("buffered", r.get("buffered"));
        if (r.get("decision_id") != null) result.put("decision", access.decision(Db.id(r, "decision_id")));
        return result;
    }

    public void exception(UUID t, UUID raw, UUID vehicle, UUID facility, String reason) {
        db.update("insert into movement_exception(id,tenant_id,raw_event_id,vehicle_id,facility_id,reason,created_at) values(?,?,?,?,?,?,?) on conflict(tenant_id,raw_event_id,reason) do nothing", UUID.randomUUID(), t, raw, vehicle, facility, reason, clock.instant());
    }

    public void match(UUID t, UUID event) {
        var e = db.one("select * from raw_movement_event where id=?", event);
        if (e.get("vehicle_id") == null) return;
        UUID vehicle = Db.id(e, "vehicle_id"), facility = Db.id(e, "facility_id");
        db.one("select pg_advisory_xact_lock(hashtextextended(?,0))", vehicle + ":" + facility);
        var evidence = db.list("select * from passage_evidence where vehicle_id=? and facility_id=? order by event_at,id", vehicle, facility);
        db.update("update movement_exception set status='RESOLVED' where vehicle_id=? and facility_id=? and reason in ('UNMATCHED_EXIT','AMBIGUOUS_PASSAGE','MISSING_EXIT')", vehicle, facility);
        // This projection is rebuildable; immutable raw passages and appended corrections remain authoritative.
        db.update("update visit set exit_passage_id=null,exit_at=null,quality='MISSING_EXIT' where vehicle_id=? and facility_id=?", vehicle, facility);
        List<Map<String, Object>> open = new ArrayList<>();
        for (var p : evidence) {
            if (Db.str(p, "direction").equals("ENTRY")) {
                db.update("insert into visit(id,tenant_id,vehicle_id,facility_id,entry_passage_id,entry_at,quality) values(?,?,?,?,?,?,'MISSING_EXIT') on conflict(tenant_id,entry_passage_id) do nothing", UUID.randomUUID(), t, vehicle, facility, Db.id(p, "id"), Db.instant(p, "event_at"));
                open.add(p);
                if (open.size() > 1) {
                    for (var entry : open) {
                        db.update("update visit set quality='AMBIGUOUS' where entry_passage_id=?", Db.id(entry, "id"));
                        exception(t, Db.id(entry, "raw_event_id"), vehicle, facility, "AMBIGUOUS_PASSAGE");
                        db.update("update movement_exception set status='OPEN' where raw_event_id=? and reason='AMBIGUOUS_PASSAGE'", Db.id(entry, "raw_event_id"));
                    }
                }
            } else if (open.size() == 1) {
                var entry = open.removeFirst();
                if (Db.instant(p, "event_at").isAfter(Db.instant(entry, "event_at")))
                    db.update("update visit set exit_passage_id=?,exit_at=?,quality='MATCHED' where entry_passage_id=?", Db.id(p, "id"), Db.instant(p, "event_at"), Db.id(entry, "id"));
                else exception(t, Db.id(p, "raw_event_id"), vehicle, facility, "AMBIGUOUS_PASSAGE");
            } else {
                String reason = open.isEmpty() ? "UNMATCHED_EXIT" : "AMBIGUOUS_PASSAGE";
                exception(t, Db.id(p, "raw_event_id"), vehicle, facility, reason);
                db.update("update movement_exception set status='OPEN' where raw_event_id=? and reason=?", Db.id(p, "raw_event_id"), reason);
            }
        }
        for (var entry : open)
            if (Db.instant(entry, "event_at").isBefore(clock.instant().minus(Duration.ofHours(24)))) {
                exception(t, Db.id(entry, "raw_event_id"), vehicle, facility, "MISSING_EXIT");
                db.update("update movement_exception set status='OPEN' where raw_event_id=? and reason='MISSING_EXIT'", Db.id(entry, "raw_event_id"));
            }
    }

    public Map<String, Object> acknowledge(UUID device, UUID command, String status) {
        var d = device(device);
        var c = db.one("select * from gate_command where id=? and gate_id=? for update", command, Db.id(d, "gate_id"));
        if (Db.str(c, "status").equals("ACKNOWLEDGED")) {
            Failure.require(Db.str(c, "acknowledgement").equals(status), 409, "ACK_PAYLOAD_MISMATCH");
            return c;
        }
        Failure.require(Db.str(c, "status").equals("DISPATCHED") && Db.instant(c, "expires_at").isAfter(clock.instant()), 409, "STALE_OR_UNDISPATCHED_COMMAND");
        db.update("update gate_command set status=?,acknowledged_at=?,acknowledgement=? where id=?", status.equals("OPENED") ? "ACKNOWLEDGED" : "FAILED", clock.instant(), status, command);
        return db.one("select * from gate_command where id=?", command);
    }
}
