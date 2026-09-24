package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.MailMessageData;

/** Called immediately before an SMTP handoff, not solely when the message was queued. */
@FunctionalInterface
public interface DeliveryPolicyPort {
    boolean mayDeliver(MailMessageData message);
    /** Default preserves compatibility with existing implementations and tests. */
    default boolean isMarketing(MailMessageData message) { return false; }
}
