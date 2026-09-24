package com.yeremitech.mailplatform.application.model;

import com.yeremitech.mailplatform.domain.ChallengeStatus;
import java.time.Instant;
import java.util.UUID;

public record PasswordChallengeData(
        UUID id,
        String clientId,
        String subjectReference,
        String email,
        String otpHash,
        ChallengeStatus status,
        int failedAttempts,
        int maxAttempts,
        Instant expiresAt,
        Instant createdAt,
        Instant consumedAt) {
}
