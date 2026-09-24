package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.PreparedMail;

public interface MailProviderPort {
    String providerKey();

    DeliveryResult send(PreparedMail mail);

    record DeliveryResult(String providerMessageId) {
    }
}
