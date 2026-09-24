package com.yeremitech.mailplatform.domain;

public enum OutboxStatus {
    PENDING,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    DEAD
}
