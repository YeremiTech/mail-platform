package com.yeremitech.mailplatform.domain;

import java.util.Objects;

public record MailRecipient(EmailAddress email, RecipientType type, String displayName) {
    public MailRecipient { Objects.requireNonNull(email); Objects.requireNonNull(type); }
}
