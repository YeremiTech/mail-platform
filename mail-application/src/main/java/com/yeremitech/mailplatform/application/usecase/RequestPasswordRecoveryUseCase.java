package com.yeremitech.mailplatform.application.usecase;

import com.yeremitech.mailplatform.application.model.PasswordChallengeData;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryRepository;
import com.yeremitech.mailplatform.application.port.PasswordRecoveryThrottlePort;
import com.yeremitech.mailplatform.application.security.SecureTokenGenerator;
import com.yeremitech.mailplatform.application.security.TokenHasher;
import com.yeremitech.mailplatform.domain.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RequestPasswordRecoveryUseCase {
    private static final Duration OTP_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(15);
    private static final long RATE_MAX = 3;

    private final PasswordRecoveryRepository recoveryRepository;
    private final PasswordRecoveryThrottlePort throttle;
    private final QueueEmailUseCase queueEmail;
    private final TokenHasher hasher;
    private final SecureTokenGenerator tokens;
    private final Clock clock;

    public RequestPasswordRecoveryUseCase(PasswordRecoveryRepository rr, PasswordRecoveryThrottlePort throttle, QueueEmailUseCase qe, TokenHasher h, SecureTokenGenerator t, Clock c) {
        recoveryRepository = rr; this.throttle = throttle; queueEmail = qe; hasher = h; tokens = t; clock = c;
    }

    public Result execute(Command c, String displayName) {
        Instant now = clock.instant();
        throttle.acquire(c.clientId(), c.subjectReference(), now, RATE_WINDOW, (int) RATE_MAX, RESEND_COOLDOWN);
        recoveryRepository.revokeActiveChallenges(c.clientId(), c.subjectReference());
        UUID id = UUID.randomUUID();
        String otp = tokens.sixDigitOtp();
        PasswordChallengeData challenge = new PasswordChallengeData(
                id, c.clientId(), c.subjectReference(), new EmailAddress(c.email()).value(),
                hasher.hash(id.toString(), otp), ChallengeStatus.ACTIVE, 0, 5,
                now.plus(OTP_TTL), now, null);
        recoveryRepository.saveChallenge(challenge);
        queueEmail.execute(new QueueEmailUseCase.Command(
                c.clientId(), "password-recovery:" + id, "Código de recuperación",
                "security/password-reset", Map.of("expiresMinutes", OTP_TTL.toMinutes(), "serviceName", displayName),
                Map.of("code", otp), challenge.expiresAt(),
                List.of(new MailRecipient(new EmailAddress(c.email()), RecipientType.TO, null)),
                List.of(), MailPriority.SECURITY, null));
        return new Result(id, challenge.expiresAt());
    }

    public record Command(String clientId, String subjectReference, String email) {}
    public record Result(UUID challengeId, Instant expiresAt) {}
}
