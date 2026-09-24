package com.yeremitech.mailplatform.application.model;

import com.yeremitech.mailplatform.domain.BatchStatus;
import java.time.Instant;
import java.util.UUID;

public record MailBatchData(
        UUID id,
        String clientId,
        String subject,
        String templateKey,
        int totalRecipients,
        BatchStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
