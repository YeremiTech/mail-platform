package com.yeremitech.mailplatform.domain;

import java.util.Objects;
import java.util.UUID;

public record AttachmentRef(
        UUID id,
        String clientId,
        String filename,
        String contentType,
        long sizeBytes,
        String storageKey,
        String checksumSha256) {
    public AttachmentRef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(filename);
        Objects.requireNonNull(contentType);
        Objects.requireNonNull(storageKey);
        Objects.requireNonNull(checksumSha256);
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must be non-negative");
    }
}
