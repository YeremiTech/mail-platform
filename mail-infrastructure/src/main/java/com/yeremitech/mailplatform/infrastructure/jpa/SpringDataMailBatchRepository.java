package com.yeremitech.mailplatform.infrastructure.jpa;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataMailBatchRepository extends JpaRepository<MailBatchEntity, UUID> {
}
