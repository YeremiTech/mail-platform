package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.domain.MailStatus;
import java.util.Optional;
import java.util.UUID;

public interface MailMessageRepository {
    MailMessageData save(MailMessageData message);
    Optional<MailMessageData> findById(UUID id);
    Optional<MailMessageData> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);
    long countByBatchId(UUID batchId);
    long countByBatchIdAndStatus(UUID batchId, MailStatus status);
}
