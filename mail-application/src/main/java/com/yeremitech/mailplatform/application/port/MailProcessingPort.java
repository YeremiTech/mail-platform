package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MailProcessingPort {
    Optional<MailMessageData> claim(UUID messageId, UUID processingToken, Instant now);
    boolean isCurrentClaim(UUID messageId, UUID processingToken);
    boolean heartbeat(UUID messageId, UUID processingToken, Instant now);
    boolean markSent(UUID messageId, UUID processingToken, String providerMessageId, Instant now);
    boolean markFailed(UUID messageId, UUID processingToken, String error, Instant now);
}
