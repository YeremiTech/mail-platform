package com.yeremitech.mailplatform.infrastructure.jpa;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SpringDataPasswordChallengeRepository extends JpaRepository<PasswordChallengeEntity, UUID> {
    Optional<PasswordChallengeEntity> findByIdAndClientId(UUID id, String clientId);
    @Modifying
    @Transactional
    @Query("update PasswordChallengeEntity c set c.status='REVOKED' where c.clientId=:clientId and c.subjectReference=:subjectReference and c.status='ACTIVE'")
    int revoke(@Param("clientId") String clientId, @Param("subjectReference") String subjectReference);

    Optional<PasswordChallengeEntity> findFirstByClientIdAndSubjectReferenceOrderByCreatedAtDesc(
            String clientId,
            String subjectReference);

    long countByClientIdAndSubjectReferenceAndCreatedAtGreaterThanEqual(
            String clientId,
            String subjectReference,
            Instant since);
}
