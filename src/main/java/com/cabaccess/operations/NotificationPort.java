package com.cabaccess.operations;

import java.util.Map;
import java.util.UUID;

public interface NotificationPort {
    String deliver(Message message);

    record Message(UUID id, String recipient, String kind, Map<String, Object> details) {
    }
}
