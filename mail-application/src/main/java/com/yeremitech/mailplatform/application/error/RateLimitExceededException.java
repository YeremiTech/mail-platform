package com.yeremitech.mailplatform.application.error;

public final class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) { super(message); }
}
