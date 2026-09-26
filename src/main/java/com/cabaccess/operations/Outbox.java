package com.cabaccess.operations;

import com.cabaccess.shared.Db;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

@Component
public class Outbox {
    private final Db db;
    private final Clock clock;

    public Outbox(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void enqueue(UUID tenant, String kind, UUID resource) {
        db.update("insert into outbox(id,tenant_id,kind,resource_id,available_at) values(?,?,?,?,?) on conflict(tenant_id,kind,resource_id) do nothing", UUID.randomUUID(), tenant, kind, resource, clock.instant());
    }

    public void notification(UUID tenant, String subject, String kind, UUID resource) {
        UUID id = UUID.randomUUID();
        if (db.update("insert into notification(id,tenant_id,subject,kind,resource_id,created_at) values(?,?,?,?,?,?) on conflict(tenant_id,kind,resource_id) do nothing", id, tenant, subject, kind, resource, clock.instant()) == 1)
            enqueue(tenant, "NOTIFY", id);
    }
}
