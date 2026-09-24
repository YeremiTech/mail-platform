package com.yeremitech.mailplatform.provider.smtp;

import com.yeremitech.mailplatform.application.model.PreparedMail;
import com.yeremitech.mailplatform.domain.EmailAddress;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmtpMailProviderTest {
    private static final String URL = "https://mail.example.org/api/v1/public/unsubscribe?token=" + "A".repeat(43);

    @Test void marketingHeadersTargetTokenizedOneClickEndpoint() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        PreparedMail mail = new PreparedMail("Offer", "<p>Offer</p>", "Offer",
                List.of(new MailRecipient(new EmailAddress("client@example.test"), RecipientType.TO, null)),
                List.of(), URL);
        new SmtpMailProvider(sender, "mailer@example.org", "Mailer", true).send(mail);
        verify(sender).send(mime);
        assertEquals("<https://mail.example.org/api/v1/public/unsubscribe/one-click?token="
                        + "A".repeat(43) + ">", mime.getHeader("List-Unsubscribe", null));
        assertEquals("List-Unsubscribe=One-Click", mime.getHeader("List-Unsubscribe-Post", null));
    }

    @Test void productionDefaultAdvertisesOneClickForMarketingMail() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        PreparedMail mail = new PreparedMail("Offer", "<p>Offer</p>", "Offer",
                List.of(new MailRecipient(new EmailAddress("client@example.test"), RecipientType.TO, null)),
                List.of(), URL);
        new SmtpMailProvider(sender, "mailer@example.org", "Mailer").send(mail);
        assertNotNull(mime.getHeader("List-Unsubscribe"));
        assertEquals("List-Unsubscribe=One-Click", mime.getHeader("List-Unsubscribe-Post", null));
    }

    @Test void transactionalMailWithoutUnsubscribeUrlHasNoCommercialHeaders() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        PreparedMail mail = new PreparedMail("Receipt", "<p>x</p>", "x",
                List.of(new MailRecipient(new EmailAddress("client@example.test"), RecipientType.TO, null)),
                List.of(), null);
        new SmtpMailProvider(sender, "mailer@example.org", "Mailer").send(mail);
        assertNull(mime.getHeader("List-Unsubscribe"));
        assertNull(mime.getHeader("List-Unsubscribe-Post"));
    }

    @Test void explicitlyDisabledRelaysDoNotAdvertiseOneClick() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        var mail = new PreparedMail("Receipt", "<p>x</p>", "x",
                List.of(new MailRecipient(new EmailAddress("client@example.test"), RecipientType.TO, null)),
                List.of(), URL);
        new SmtpMailProvider(sender, "mailer@example.org", "Mailer", false).send(mail);
        assertNull(mime.getHeader("List-Unsubscribe"));
        assertNull(mime.getHeader("List-Unsubscribe-Post"));
    }
    @Test void invalidMarketingUnsubscribeLinkIsPermanentAndNotSent() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mime);
        PreparedMail message = new PreparedMail("Marketing", "<p>Sale</p>", "Sale",
                List.of(new MailRecipient(new EmailAddress("client@example.test"), RecipientType.TO, null)),
                List.of(), "https://mail.example.org/api/v1/public/unsubscribe?token=wrong");
        assertThrows(IllegalArgumentException.class,
                () -> new SmtpMailProvider(sender, "mailer@example.org", "Mailer").send(message));
        verify(sender, never()).send(mime);
    }

}
