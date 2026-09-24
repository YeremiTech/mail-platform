package com.yeremitech.mailplatform.infrastructure.jpa;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAttachmentRepository extends JpaRepository<AttachmentEntity, UUID> {
    Optional<AttachmentEntity> findByIdAndClientId(UUID id, String clientId);
    boolean existsByIdAndClientId(UUID id, String clientId);
}
