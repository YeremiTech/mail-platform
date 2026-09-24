package com.yeremitech.mailplatform.application.model;

import com.yeremitech.mailplatform.domain.MailRecipient;
import java.nio.file.Path;
import java.util.List;

/** Attachments are bounded on-disk spools, not byte arrays retained in the worker heap. */
public record PreparedMail(String subject, String htmlBody, String textBody,
                           List<MailRecipient> recipients, List<PreparedAttachment> attachments,
                           String oneClickUnsubscribeUrl) {
    public PreparedMail(String subject, String htmlBody, String textBody,
                        List<MailRecipient> recipients, List<PreparedAttachment> attachments) {
        this(subject, htmlBody, textBody, recipients, attachments, null);
    }
    public record PreparedAttachment(String filename, String contentType, Path path) {}
}
