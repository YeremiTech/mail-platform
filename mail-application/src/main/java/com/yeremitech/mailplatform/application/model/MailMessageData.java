package com.yeremitech.mailplatform.application.model;

import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.MailStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MailMessageData(
        UUID id,
        String clientId,
        String idempotencyKey,
        String subject,
        String templateKey,
        Map<String, Object> variables,
        List<MailRecipient> recipients,
        List<UUID> attachmentIds,
        MailPriority priority,
        MailStatus status,
        UUID batchId,
        Instant createdAt,
        Instant updatedAt,
        String lastError,
        String providerMessageId,
        Instant scheduledAt) {
    public MailMessageData(UUID id, String clientId, String idempotencyKey, String subject, String templateKey,
            Map<String, Object> variables, List<MailRecipient> recipients, List<UUID> attachmentIds,
            MailPriority priority, MailStatus status, UUID batchId, Instant createdAt, Instant updatedAt,
            String lastError, String providerMessageId) {
        this(id, clientId, idempotencyKey, subject, templateKey, variables, recipients, attachmentIds,
                priority, status, batchId, createdAt, updatedAt, lastError, providerMessageId, null);
    }
}
