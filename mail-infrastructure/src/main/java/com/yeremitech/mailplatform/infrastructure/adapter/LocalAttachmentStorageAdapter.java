package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.domain.AttachmentRef;
import com.yeremitech.mailplatform.infrastructure.jpa.AttachmentEntity;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataAttachmentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

public final class LocalAttachmentStorageAdapter implements AttachmentStoragePort {
    private final Path root;
    private final SpringDataAttachmentRepository repository;

    public LocalAttachmentStorageAdapter(Path root, SpringDataAttachmentRepository repository) {
        this.root = root.toAbsolutePath().normalize();
        this.repository = repository;
        try { Files.createDirectories(this.root); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @Override
    public AttachmentRef store(String clientId, String filename, String type, InputStream in, long size) {
        Path target = null;
        try {
            UUID id = UUID.randomUUID();
            String safe = Path.of(filename).getFileName().toString();
            target = root.resolve(id + "-" + safe).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("invalid attachment filename");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
                 var din = new java.security.DigestInputStream(in, md)) {
                din.transferTo(out);
            }
            var e = new AttachmentEntity();
            e.id=id; e.clientId=clientId; e.filename=safe; e.contentType=type; e.sizeBytes=Files.size(target);
            e.storageKey=target.getFileName().toString(); e.checksumSha256=HexFormat.of().formatHex(md.digest()); e.createdAt=Instant.now();
            repository.save(e);
            return toRef(e);
        } catch (Exception e) {
            if (target != null) { try { Files.deleteIfExists(target); } catch (IOException ignored) {} }
            throw new IllegalStateException("unable to store attachment", e);
        }
    }

    @Override
    public Optional<StoredAttachment> load(String clientId, UUID id) {
        return repository.findByIdAndClientId(id, clientId).map(e -> {
            try {
                Path path = root.resolve(e.storageKey).normalize();
                if (!path.startsWith(root)) throw new IllegalStateException("invalid storage key");
                return new StoredAttachment(toRef(e), Files.newInputStream(path));
            } catch (IOException x) { throw new IllegalStateException(x); }
        });
    }

    @Override
    public boolean belongsTo(String clientId, UUID attachmentId) {
        return repository.existsByIdAndClientId(attachmentId, clientId);
    }

    private AttachmentRef toRef(AttachmentEntity e) {
        return new AttachmentRef(e.id, e.clientId, e.filename, e.contentType, e.sizeBytes, e.storageKey, e.checksumSha256);
    }
}
