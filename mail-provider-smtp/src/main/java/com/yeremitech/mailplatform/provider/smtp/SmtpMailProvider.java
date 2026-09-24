package com.yeremitech.mailplatform.provider.smtp;

import com.yeremitech.mailplatform.application.model.PreparedMail;
import com.yeremitech.mailplatform.application.port.MailProviderPort;
import com.yeremitech.mailplatform.domain.RecipientType;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import java.net.URI;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public final class SmtpMailProvider implements MailProviderPort {
    private final JavaMailSender sender;
    private final String from;
    private final String fromName;
    private final boolean oneClickEnabled;

    public SmtpMailProvider(JavaMailSender sender, String from, String fromName) {
        this(sender, from, fromName, true);
    }

    /** Enable only when the downstream SMTP relay DKIM-signs both List-Unsubscribe headers. */
    public SmtpMailProvider(JavaMailSender sender, String from, String fromName, boolean oneClickEnabled) {
        this.sender = sender;
        this.from = from;
        this.fromName = fromName;
        this.oneClickEnabled = oneClickEnabled;
    }

    @Override
    public String providerKey() {
        return "smtp";
    }

    @Override
    public DeliveryResult send(PreparedMail mail) {
        try {
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from, fromName);
            helper.setSubject(mail.subject());
            helper.setText(mail.textBody(), mail.htmlBody());

            for (var recipient : mail.recipients()) {
                if (recipient.type() == RecipientType.TO) {
                    helper.addTo(recipient.email().value());
                } else if (recipient.type() == RecipientType.CC) {
                    helper.addCc(recipient.email().value());
                } else {
                    helper.addBcc(recipient.email().value());
                }
            }

            for (var attachment : mail.attachments()) {
                helper.addAttachment(
                        attachment.filename(),
                        new FileSystemResource(attachment.path()),
                        attachment.contentType());
            }

            if (oneClickEnabled && mail.oneClickUnsubscribeUrl() != null) {
                URI link = URI.create(mail.oneClickUnsubscribeUrl());
                // RFC 8058 one-click MUST use HTTPS. Local HTTP development still receives body links.
                if ("https".equalsIgnoreCase(link.getScheme()) && link.getUserInfo() == null
                        && link.getFragment() == null && link.getHost() != null
                        && "/api/v1/public/unsubscribe".equals(link.getPath())
                        && link.getRawQuery() != null
                        && link.getRawQuery().matches("token=[A-Za-z0-9_-]{43}")) {
                    // The one-click endpoint requires the RFC 8058 form field. The visible
                    // unsubscribe link still goes to the confirmation page.
                    URI oneClick = new URI(link.getScheme(), link.getRawAuthority(),
                            "/api/v1/public/unsubscribe/one-click", link.getRawQuery(), null);
                    mimeMessage.setHeader("List-Unsubscribe", "<" + oneClick.toASCIIString() + ">");
                    mimeMessage.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                } else if (!"http".equalsIgnoreCase(link.getScheme()) || !isDevelopmentHost(link)) {
                    throw new IllegalArgumentException("one-click unsubscribe requires an HTTPS URL");
                }
            }
            sender.send(mimeMessage);
            String messageId = mimeMessage.getMessageID();
            return new DeliveryResult(
                    messageId == null ? UUID.randomUUID().toString() : messageId);
        } catch (IllegalArgumentException permanent) {
            // Invalid unsubscribe URLs or recipients cannot succeed on a retry.
            throw permanent;
        } catch (Exception ex) {
            throw new IllegalStateException("SMTP delivery failed", ex);
        }
    }

    private static boolean isDevelopmentHost(URI uri) {
        return "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                || "[::1]".equals(uri.getHost());
    }
}
