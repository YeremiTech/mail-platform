package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.PasswordChallengeData;
import com.yeremitech.mailplatform.application.model.ResetGrantData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordRecoveryRepository {
    PasswordChallengeData saveChallenge(PasswordChallengeData challenge);
    Optional<PasswordChallengeData> findChallenge(String clientId, UUID id);
    Optional<PasswordChallengeData> findLatestChallenge(String clientId, String subjectReference);
    long countChallengesSince(String clientId, String subjectReference, Instant since);
    void revokeActiveChallenges(String clientId, String subjectReference);
    boolean recordFailedAttempt(String clientId, UUID challengeId, Instant now);
    Optional<PasswordChallengeData> consumeActiveChallenge(String clientId, UUID challengeId, Instant now);
    Optional<PasswordChallengeData> consumeChallengeAndSaveGrant(String clientId, UUID challengeId, Instant now, ResetGrantData grant);
    ResetGrantData saveGrant(ResetGrantData grant);
    Optional<ResetGrantData> findGrant(String clientId, UUID id);
    Optional<ResetGrantData> consumeGrant(String clientId, UUID grantId, Instant now);
}
