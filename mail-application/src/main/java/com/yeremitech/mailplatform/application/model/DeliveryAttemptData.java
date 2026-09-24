package com.yeremitech.mailplatform.application.model;

import com.yeremitech.mailplatform.domain.DeliveryAttemptStatus;
import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptData(
        UUID id,
        UUID messageId,
        int attemptNumber,
        String providerKey,
        DeliveryAttemptStatus status,
        Instant startedAt,
        Instant finishedAt,
        String providerMessageId,
        String errorType,
        String errorMessage) {
}
