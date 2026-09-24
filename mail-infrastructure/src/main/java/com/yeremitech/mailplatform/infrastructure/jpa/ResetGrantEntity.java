package com.yeremitech.mailplatform.infrastructure.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_grant")
public class ResetGrantEntity {
    @Id public UUID id;
    @Column(nullable = false) public UUID challengeId;
    @Column(nullable = false) public String clientId;
    @Column(nullable = false) public String subjectReference;
    @Column(nullable = false, length = 64) public String tokenHash;
    @Column(nullable = false) public Instant expiresAt;
    @Column(nullable = false) public Instant createdAt;
    public Instant consumedAt;
    public ResetGrantEntity() {}
}
