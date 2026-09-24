package com.yeremitech.mailplatform.application.model;

import java.time.Instant;
import java.util.UUID;

public record ResetGrantData(UUID id, UUID challengeId, String clientId, String subjectReference, String tokenHash, Instant expiresAt, Instant createdAt, Instant consumedAt) {
    public boolean consumed() { return consumedAt != null; }
}
