package com.yeremitech.mailplatform.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mail_attachment")
public class AttachmentEntity {
    @Id public UUID id;
    @Column(nullable = false) public String clientId;
    @Column(nullable = false) public String filename;
    @Column(nullable = false) public String contentType;
    @Column(nullable = false) public long sizeBytes;
    @Column(nullable = false, unique = true) public String storageKey;
    @Column(nullable = false, length = 64) public String checksumSha256;
    @Column(nullable = false) public Instant createdAt;
    public AttachmentEntity() {}
}
