package com.yeremitech.mailplatform.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mail_batch")
public class MailBatchEntity {
    @Id
    public UUID id;

    @Column(nullable = false)
    public String clientId;

    @Column(nullable = false)
    public String subject;

    @Column(nullable = false)
    public String templateKey;

    @Column(nullable = false)
    public int totalRecipients;

    @Column(nullable = false)
    public String status;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    public MailBatchEntity() {
    }
}
