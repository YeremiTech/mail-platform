package com.yeremitech.mailplatform.infrastructure.jpa;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataResetGrantRepository extends JpaRepository<ResetGrantEntity, UUID> {
    Optional<ResetGrantEntity> findByIdAndClientId(UUID id, String clientId);
}
