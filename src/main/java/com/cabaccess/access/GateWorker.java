package com.cabaccess.access;

import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.Db;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Component
public class GateWorker {
    private final Db db;
    private final TenantTx tx;
    private final Clock clock;
    private final GatePort adapter;

    public GateWorker(Db db, TenantTx tx, Clock clock, GatePort adapter) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.adapter = adapter;
    }

    public void dispatch(UUID t, UUID id) {
        var command = tx.system(t, () -> {
            var c = db.one("select * from gate_command where id=? for update", id);
            if (!Db.str(c, "status").equals("PENDING")) return Optional.<GatePort.Command>empty();
            if (!Db.instant(c, "expires_at").isAfter(clock.instant())) {
                db.update("update gate_command set status='EXPIRED' where id=?", id);
                return Optional.<GatePort.Command>empty();
            }
            db.update("update gate_command set status='DISPATCHED',claimed_at=? where id=?", clock.instant(), id);
            return Optional.of(new GatePort.Command(id, t, Db.id(c, "gate_id"), Db.instant(c, "expires_at")));
        });
        if (command.isEmpty()) return;
        try {
            adapter.dispatch(command.get());
        } catch (RuntimeException e) {
            tx.system(t, () -> {
                db.update("update gate_command set status='UNKNOWN' where id=? and status='DISPATCHED'", id);
                return true;
            });/* Never retry a possibly executed physical command. */
        }
    }
}
