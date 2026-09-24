package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.PasswordChallengeData;
import com.yeremitech.mailplatform.application.model.ResetGrantData;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryRepository;
import com.yeremitech.mailplatform.domain.ChallengeStatus;
import com.yeremitech.mailplatform.infrastructure.jpa.PasswordChallengeEntity;
import com.yeremitech.mailplatform.infrastructure.jpa.ResetGrantEntity;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataPasswordChallengeRepository;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataResetGrantRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JpaPasswordRecoveryAdapter implements PasswordRecoveryRepository {
    private final SpringDataPasswordChallengeRepository challenges;
    private final SpringDataResetGrantRepository grants;
    private final NamedParameterJdbcTemplate jdbc;

    public JpaPasswordRecoveryAdapter(
            SpringDataPasswordChallengeRepository challenges,
            SpringDataResetGrantRepository grants,
            NamedParameterJdbcTemplate jdbc) {
        this.challenges = challenges;
        this.grants = grants;
        this.jdbc = jdbc;
    }

    public PasswordChallengeData saveChallenge(PasswordChallengeData d) {
        var e = new PasswordChallengeEntity();
        e.id=d.id(); e.clientId=d.clientId(); e.subjectReference=d.subjectReference(); e.email=d.email(); e.otpHash=d.otpHash();
        e.status=d.status().name(); e.failedAttempts=d.failedAttempts(); e.maxAttempts=d.maxAttempts(); e.expiresAt=d.expiresAt();
        e.createdAt=d.createdAt(); e.consumedAt=d.consumedAt();
        return toData(challenges.save(e));
    }

    public Optional<PasswordChallengeData> findChallenge(String clientId, UUID id) { return challenges.findByIdAndClientId(id, clientId).map(this::toData); }
    public Optional<PasswordChallengeData> findLatestChallenge(String clientId, String subjectReference) { return challenges.findFirstByClientIdAndSubjectReferenceOrderByCreatedAtDesc(clientId, subjectReference).map(this::toData); }
    public long countChallengesSince(String clientId, String subjectReference, Instant since) { return challenges.countByClientIdAndSubjectReferenceAndCreatedAtGreaterThanEqual(clientId, subjectReference, since); }
    public void revokeActiveChallenges(String clientId, String subjectReference) { challenges.revoke(clientId, subjectReference); }

    @Override
    public boolean recordFailedAttempt(String clientId, UUID challengeId, Instant now) {
        int updated = jdbc.update("""
                update password_reset_challenge
                set failed_attempts = failed_attempts + 1,
                    status = case when failed_attempts + 1 >= max_attempts then 'BLOCKED' else status end
                where id=:id and client_id=:clientId and status='ACTIVE' and expires_at > :now
                """, Map.of("id", challengeId, "clientId", clientId, "now", JdbcTime.value(now)));
        return updated == 1;
    }

    @Override
    @Transactional
    public Optional<PasswordChallengeData> consumeActiveChallenge(String clientId, UUID challengeId, Instant now) {
        var rows = jdbc.query("""
                update password_reset_challenge
                set status='CONSUMED', consumed_at=:now
                where id=:id and client_id=:clientId and status='ACTIVE' and expires_at > :now and failed_attempts < max_attempts
                returning *
                """, Map.of("id", challengeId, "clientId", clientId, "now", JdbcTime.value(now)), this::mapChallenge);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<PasswordChallengeData> consumeChallengeAndSaveGrant(String clientId, UUID challengeId, Instant now, ResetGrantData grant) {
        Optional<PasswordChallengeData> consumed = consumeActiveChallenge(clientId, challengeId, now);
        if (consumed.isEmpty()) return Optional.empty();
        saveGrant(grant);
        return consumed;
    }

    public ResetGrantData saveGrant(ResetGrantData d) {
        var e = new ResetGrantEntity();
        e.id=d.id(); e.challengeId=d.challengeId(); e.clientId=d.clientId(); e.subjectReference=d.subjectReference(); e.tokenHash=d.tokenHash();
        e.expiresAt=d.expiresAt(); e.createdAt=d.createdAt(); e.consumedAt=d.consumedAt();
        return toData(grants.save(e));
    }

    public Optional<ResetGrantData> findGrant(String clientId, UUID id) { return grants.findByIdAndClientId(id, clientId).map(this::toData); }

    @Override
    @Transactional
    public Optional<ResetGrantData> consumeGrant(String clientId, UUID grantId, Instant now) {
        var rows = jdbc.query("""
                update password_reset_grant
                set consumed_at=:now
                where id=:id and client_id=:clientId and consumed_at is null and expires_at > :now
                returning *
                """, Map.of("id", grantId, "clientId", clientId, "now", JdbcTime.value(now)), this::mapGrant);
        return rows.stream().findFirst();
    }

    private PasswordChallengeData mapChallenge(ResultSet rs, int rowNum) throws SQLException {
        var consumed = rs.getTimestamp("consumed_at");
        return new PasswordChallengeData(
                rs.getObject("id", UUID.class), rs.getString("client_id"), rs.getString("subject_reference"), rs.getString("email"),
                rs.getString("otp_hash"), ChallengeStatus.valueOf(rs.getString("status")), rs.getInt("failed_attempts"), rs.getInt("max_attempts"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(), consumed == null ? null : consumed.toInstant());
    }

    private ResetGrantData mapGrant(ResultSet rs, int rowNum) throws SQLException {
        var consumed = rs.getTimestamp("consumed_at");
        return new ResetGrantData(
                rs.getObject("id", UUID.class), rs.getObject("challenge_id", UUID.class), rs.getString("client_id"), rs.getString("subject_reference"),
                rs.getString("token_hash"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                consumed == null ? null : consumed.toInstant());
    }

    private PasswordChallengeData toData(PasswordChallengeEntity e) {
        return new PasswordChallengeData(e.id,e.clientId,e.subjectReference,e.email,e.otpHash,ChallengeStatus.valueOf(e.status),e.failedAttempts,e.maxAttempts,e.expiresAt,e.createdAt,e.consumedAt);
    }
    private ResetGrantData toData(ResetGrantEntity e) {
        return new ResetGrantData(e.id,e.challengeId,e.clientId,e.subjectReference,e.tokenHash,e.expiresAt,e.createdAt,e.consumedAt);
    }
}
