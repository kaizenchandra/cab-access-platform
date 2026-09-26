package com.cabaccess.access;

import java.time.Instant;
import java.util.UUID;

public interface GatePort {
  record Command(UUID id,UUID tenant,UUID gate,Instant expiresAt){}
  void dispatch(Command command);
}
