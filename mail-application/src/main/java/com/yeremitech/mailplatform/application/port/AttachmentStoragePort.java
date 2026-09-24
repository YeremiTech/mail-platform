package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.domain.AttachmentRef;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentStoragePort {
    AttachmentRef store(String clientId, String filename, String contentType, InputStream content, long sizeBytes);
    Optional<StoredAttachment> load(String clientId, UUID attachmentId);
    boolean belongsTo(String clientId, UUID attachmentId);
    record StoredAttachment(AttachmentRef metadata, InputStream content) {}
}
