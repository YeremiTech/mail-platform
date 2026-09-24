package com.yeremitech.mailplatform.worker;

import com.yeremitech.mailplatform.application.model.DeliveryAttemptData;
import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.model.PreparedMail;
import com.yeremitech.mailplatform.application.port.*;
import com.yeremitech.mailplatform.domain.DeliveryAttemptStatus;
import com.yeremitech.mailplatform.domain.ChallengeStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MailDeliveryWorker {
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 30L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(MailDeliveryWorker.class);

    private final MailProcessingPort processing;
    private final TemplateRendererPort renderer;
    private final MailProviderPort provider;
    private final AttachmentStoragePort storage;
    private final SensitivePayloadPort sensitivePayloads;
    private final PasswordRecoveryRepository recovery;
    private final DeliveryAttemptRepository attempts;
    private final MailRetryPort retry;
    private final DeliveryPolicyPort policy;
    private final Clock clock;
    private final int maxDeliveryAttempts;
    private final Map<UUID, UUID> activeClaims = new ConcurrentHashMap<>();

    public MailDeliveryWorker(
            MailProcessingPort processing,
            TemplateRendererPort renderer,
            MailProviderPort provider,
            AttachmentStoragePort storage,
            SensitivePayloadPort sensitivePayloads,
            PasswordRecoveryRepository recovery,
            DeliveryAttemptRepository attempts,
            MailRetryPort retry,
            Clock clock,
            int maxDeliveryAttempts) {
        this(processing,renderer,provider,storage,sensitivePayloads,recovery,attempts,retry,clock,
                maxDeliveryAttempts,message->true);
    }

    public MailDeliveryWorker(
            MailProcessingPort processing, TemplateRendererPort renderer, MailProviderPort provider,
            AttachmentStoragePort storage, SensitivePayloadPort sensitivePayloads,
            PasswordRecoveryRepository recovery, DeliveryAttemptRepository attempts, MailRetryPort retry,
            Clock clock, int maxDeliveryAttempts, DeliveryPolicyPort policy) {
        this.policy=Objects.requireNonNull(policy);
        this.processing=processing; this.renderer=renderer; this.provider=provider;
        this.storage=storage; this.sensitivePayloads=sensitivePayloads; this.recovery=recovery; this.attempts=attempts; this.retry=retry;
        this.clock=clock; this.maxDeliveryAttempts=maxDeliveryAttempts;
    }

    @RabbitListener(queues = "mail.security", concurrency = "2-4") public void security(String rawId) { process(rawId); }
    @RabbitListener(queues = "mail.transactional", concurrency = "2-4") public void transactional(String rawId) { process(rawId); }
    @RabbitListener(queues = "mail.document", concurrency = "1-3") public void document(String rawId) { process(rawId); }
    @RabbitListener(queues = "mail.bulk", concurrency = "1-2") public void bulk(String rawId) { process(rawId); }

    @Scheduled(fixedDelayString = "${app.delivery.heartbeat-interval-ms:30000}")
    public void heartbeatActiveClaims() {
        Instant now = clock.instant();
        activeClaims.forEach((id, token) -> {
            try {
                if (!processing.heartbeat(id, token, now)) {
                    activeClaims.remove(id, token);
                    log.warn("Mail processing claim was lost for message {}", id);
                }
            } catch (RuntimeException ex) {
                log.warn("Could not renew mail processing claim for message {}", id, ex);
            }
        });
    }

    void process(String rawId) {
        UUID id = UUID.fromString(rawId);
        Instant startedAt = clock.instant();
        UUID token = UUID.randomUUID();
        MailMessageData message = processing.claim(id, token, startedAt).orElse(null);
        if (message == null) return;

        activeClaims.put(id, token);
        try {
            processClaimed(message, token, startedAt);
        } finally {
            activeClaims.remove(id, token);
        }
    }

    private void processClaimed(MailMessageData message, UUID token, Instant startedAt) {
        UUID id = message.id();
        int attemptNumber = attempts.nextAttemptNumber(id);
        UUID attemptId = UUID.randomUUID();
        attempts.save(new DeliveryAttemptData(
                attemptId, id, attemptNumber, provider.providerKey(), DeliveryAttemptStatus.STARTED,
                startedAt, null, null, null, null));

        MailProviderPort.DeliveryResult result;
        List<PreparedMail.PreparedAttachment> preparedAttachments = List.of();
        try {
            Map<String, Object> renderVariables = new HashMap<>(message.variables());
            renderVariables.putAll(sensitivePayloads.load(id));
            validateRecoveryMail(message, renderVariables, clock.instant());
            var rendered = renderer.render(message.clientId(), message.templateKey(), renderVariables);
            preparedAttachments = loadAttachments(message);
            validateRecoveryMail(message, renderVariables, clock.instant());
            if (!processing.isCurrentClaim(id, token)) {
                attempts.save(new DeliveryAttemptData(
                        attemptId, id, attemptNumber, provider.providerKey(), DeliveryAttemptStatus.FAILED,
                        startedAt, clock.instant(), null, "CLAIM_LOST", "mail processing claim was recovered"));
                return;
            }
            // Recheck immediately before the external handoff; queued opt-outs must not be sent.
            if (!policy.mayDeliver(message)) throw new IllegalArgumentException("marketing recipient is suppressed");
            String html=rendered.html(); String text=rendered.text();
            String oneClickUrl = null;
            if(policy.isMarketing(message)) {
                var footer=com.yeremitech.mailplatform.application.util.MarketingFooter.apply(html,text,
                        (String) renderVariables.get("unsubscribeUrl"));
                html=footer.html();text=footer.text();
                oneClickUrl = (String) renderVariables.get("unsubscribeUrl");
            }
            result = provider.send(new PreparedMail(
                    message.subject(), html, text, message.recipients(), preparedAttachments, oneClickUrl));
        } catch (Exception ex) {
            Instant failedAt = clock.instant();
            String error = rootMessage(ex);
            attempts.save(new DeliveryAttemptData(
                    attemptId, id, attemptNumber, provider.providerKey(), DeliveryAttemptStatus.FAILED,
                    startedAt, failedAt, null, ex.getClass().getName(), error));

            boolean permanent = ex instanceof IllegalArgumentException;
            if (!permanent && attemptNumber < maxDeliveryAttempts) {
                retry.scheduleRetry(id, token, message.priority(), failedAt.plus(deliveryBackoff(attemptNumber)), error, failedAt);
            } else {
                if (processing.markFailed(id, token, error, failedAt)) sensitivePayloads.purge(id);
            }
            return;
        } finally {
            deleteSpools(preparedAttachments);
        }

        Instant finishedAt = clock.instant();
        boolean finalized = processing.markSent(id, token, result.providerMessageId(), finishedAt);
        attempts.save(new DeliveryAttemptData(
                attemptId, id, attemptNumber, provider.providerKey(), DeliveryAttemptStatus.SUCCEEDED,
                startedAt, finishedAt, result.providerMessageId(), null, null));
        if (finalized) sensitivePayloads.purge(id);
        else log.error("SMTP accepted message {} after its processing claim was lost; inspect for possible duplicate delivery", id);
    }

    private void validateRecoveryMail(MailMessageData message, Map<String, Object> variables, Instant now) {
        if (!"security/password-reset".equals(message.templateKey())) return;
        String key = message.idempotencyKey();
        if (key == null || !key.startsWith("password-recovery:")) {
            throw new IllegalArgumentException("invalid recovery mail reference");
        }
        UUID challengeId;
        try {
            challengeId = UUID.fromString(key.substring("password-recovery:".length()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid recovery mail reference", ex);
        }
        var challenge = recovery.findChallenge(message.clientId(), challengeId)
                .orElseThrow(() -> new IllegalArgumentException("recovery challenge no longer exists"));
        if (challenge.status() != ChallengeStatus.ACTIVE || !now.isBefore(challenge.expiresAt())
                || !(variables.get("code") instanceof String code) || !code.matches("\\d{6}")) {
            throw new IllegalArgumentException("recovery code has expired or was revoked");
        }
    }

    /** Read one attachment at a time; temporary files are deleted after synchronous SMTP handoff. */
    private List<PreparedMail.PreparedAttachment> loadAttachments(MailMessageData message) throws Exception {
        List<PreparedMail.PreparedAttachment> files = new ArrayList<>();
        long total = 0;
        try {
            for (UUID id : message.attachmentIds()) {
                var stored = storage.load(message.clientId(), id)
                        .orElseThrow(() -> new IllegalArgumentException("attachment not found for authenticated client"));
                long expected = stored.metadata().sizeBytes();
                total += expected;
                if (expected < 1 || expected > 15L * 1024 * 1024 || total > MAX_TOTAL_ATTACHMENT_BYTES) {
                    try (var ignored = stored.content()) { /* release a database large-object stream */ }
                    throw new IllegalArgumentException("attachment size exceeds delivery limit");
                }
                // Acquire the stream before allocating the spool: a failed temp allocation
                // must not leak the PostgreSQL connection held by the large-object stream.
                try (InputStream in = stored.content()) {
                    Path tmp = Files.createTempFile("mail-attachment-", ".spool");
                    files.add(new PreparedMail.PreparedAttachment(stored.metadata().filename(),
                            stored.metadata().contentType(), tmp));
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long copied = 0;
                    try (OutputStream out = Files.newOutputStream(tmp)) {
                        byte[] buffer = new byte[32 * 1024];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            copied += n;
                            if (copied > expected) throw new IllegalArgumentException("attachment size changed");
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                        }
                    }
                    if (copied != expected || !HexFormat.of().formatHex(digest.digest())
                            .equalsIgnoreCase(stored.metadata().checksumSha256())) {
                        throw new IllegalArgumentException("attachment integrity verification failed");
                    }
                }
            }
            return files;
        } catch (Exception ex) {
            deleteSpools(files);
            throw ex;
        }
    }

    private static void deleteSpools(List<PreparedMail.PreparedAttachment> files) {
        for (var attachment : files) {
            try { Files.deleteIfExists(attachment.path()); }
            catch (java.io.IOException e) {
                log.warn("Unable to delete temporary attachment spool", e);
            }
        }
    }

    private static Duration deliveryBackoff(int attemptNumber) {
        return switch (attemptNumber) {
            case 1 -> Duration.ofSeconds(30);
            case 2 -> Duration.ofMinutes(2);
            case 3 -> Duration.ofMinutes(10);
            case 4 -> Duration.ofMinutes(30);
            default -> Duration.ofHours(1);
        };
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getName() + (message == null ? "" : ": " + message);
    }

}
