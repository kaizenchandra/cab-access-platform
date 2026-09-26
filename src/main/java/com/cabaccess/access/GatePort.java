package com.cabaccess.access;

import java.time.Instant;
import java.util.UUID;

public interface GatePort {
    void dispatch(Command command);

    record Command(UUID id, UUID tenant, UUID gate, Instant expiresAt) {
    }
}
