package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.OutboxEventData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {
    List<OutboxEventData> claimReady(Instant now, Instant lockedUntil, int limit);
    boolean markPublished(UUID eventId, Instant lockedUntil, Instant publishedAt);
    boolean markFailed(UUID eventId, Instant lockedUntil, int attempts, Instant nextAttemptAt, boolean terminal, String error, Instant now);
}
