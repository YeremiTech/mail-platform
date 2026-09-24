package com.yeremitech.mailplatform.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mail_message")
public class MailMessageEntity {
    @Id public UUID id;
    @Column(nullable=false) public String clientId;
    public String idempotencyKey;
    @Column(nullable=false) public String subject;
    @Column(nullable=false) public String templateKey;
    @Column(nullable=false,columnDefinition="text") public String variablesJson;
    @Column(nullable=false,columnDefinition="text") public String recipientsJson;
    @Column(nullable=false,columnDefinition="text") public String attachmentIdsJson;
    @Column(nullable=false) public String priority;
    @Column(nullable=false) public String status;
    public UUID batchId;
    @Column(nullable=false) public Instant createdAt;
    @Column(nullable=false) public Instant updatedAt;
    @Column(columnDefinition="text") public String lastError;
    public String providerMessageId;
    public UUID processingToken;
    public Instant processingStartedAt;
    public Instant scheduledAt;
    public MailMessageEntity() {}
}
