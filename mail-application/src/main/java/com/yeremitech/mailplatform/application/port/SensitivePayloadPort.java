package com.yeremitech.mailplatform.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface SensitivePayloadPort {
    void store(UUID messageId, Map<String, Object> values, Instant expiresAt);
    Map<String, Object> load(UUID messageId);
    void purge(UUID messageId);
    int purgeExpired(Instant now, int limit);
}
