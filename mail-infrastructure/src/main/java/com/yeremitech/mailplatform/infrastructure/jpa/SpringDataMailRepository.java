package com.yeremitech.mailplatform.infrastructure.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataMailRepository extends JpaRepository<MailMessageEntity, UUID> {
    Optional<MailMessageEntity> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);
    long countByBatchId(UUID batchId);
    long countByBatchIdAndStatus(UUID batchId, String status);
}
