package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.domain.MailPriority;
import java.time.Instant;
import java.util.UUID;

public interface MailRetryPort {
    boolean scheduleRetry(UUID messageId, UUID processingToken, MailPriority priority, Instant nextAttemptAt, String lastError, Instant now);
    int recoverStaleProcessing(Instant olderThan, Instant now, int limit);
}
