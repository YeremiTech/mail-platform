package com.yeremitech.mailplatform.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EmailAddress(String value) {
    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public EmailAddress {
        Objects.requireNonNull(value, "email is required");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 320 || !SIMPLE_EMAIL.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid email address");
        }
    }
}
