package com.cabaccess.shared;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class Idempotency {
    private final Db db;

    public Idempotency(Db db) {
        this.db = db;
    }

    public Map<String, Object> execute(UUID tenant, String subject, String operation, String key, Object payload, Supplier<Map<String, Object>> work) {
        Failure.require(key != null && key.length() >= 8 && key.length() <= 128, 400, "IDEMPOTENCY_KEY_REQUIRED");
        String hash = Crypto.hash(db.json(payload));
        db.one("select pg_advisory_xact_lock(hashtextextended(?,0))", tenant + ":" + subject + ":" + operation + ":" + key);
        var old = db.optional("select * from idempotency_record where tenant_id=? and subject=? and operation=? and request_key=?", tenant, subject, operation, key);
        if (old.isPresent()) {
            Failure.require(hash.equals(Db.str(old.get(), "payload_hash")), 409, "IDEMPOTENCY_PAYLOAD_MISMATCH");
            return db.parse(Db.str(old.get(), "response"));
        }
        var result = work.get();
        db.update("insert into idempotency_record(id,tenant_id,subject,operation,request_key,payload_hash,response) values(?,?,?,?,?,?,?)", UUID.randomUUID(), tenant, subject, operation, key, hash, db.json(result));
        return result;
    }
}
