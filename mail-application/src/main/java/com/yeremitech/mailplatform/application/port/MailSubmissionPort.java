package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import java.time.Instant;
import java.util.Map;

public interface MailSubmissionPort {
    MailMessageData submit(MailMessageData message, Map<String, Object> sensitiveVariables, Instant sensitiveExpiresAt);
}
