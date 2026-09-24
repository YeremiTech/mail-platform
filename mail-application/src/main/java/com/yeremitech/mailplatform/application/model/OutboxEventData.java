package com.yeremitech.mailplatform.application.model;

import com.yeremitech.mailplatform.domain.OutboxStatus;
import java.time.Instant;
import java.util.UUID;

public record OutboxEventData(
        UUID id,
        UUID aggregateId,
        String eventType,
        String routingKey,
        String payload,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant lockedUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        String lastError) {
}
