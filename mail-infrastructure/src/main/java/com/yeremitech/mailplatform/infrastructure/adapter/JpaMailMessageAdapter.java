package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.MailMessageData;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.domain.MailPriority;
import com.yeremitech.mailplatform.domain.MailRecipient;
import com.yeremitech.mailplatform.domain.MailStatus;
import com.yeremitech.mailplatform.infrastructure.jpa.MailMessageEntity;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataMailRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class JpaMailMessageAdapter implements MailMessageRepository {
    private final SpringDataMailRepository repository;
    private final JsonMapper json;

    public JpaMailMessageAdapter(SpringDataMailRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Override
    public MailMessageData save(MailMessageData data) {
        try {
            MailMessageEntity entity = new MailMessageEntity();
            entity.id = data.id();
            entity.clientId = data.clientId();
            entity.idempotencyKey = data.idempotencyKey();
            entity.subject = data.subject();
            entity.templateKey = data.templateKey();
            entity.variablesJson = json.writeValueAsString(data.variables());
            entity.recipientsJson = json.writeValueAsString(data.recipients());
            entity.attachmentIdsJson = json.writeValueAsString(data.attachmentIds());
            entity.priority = data.priority().name();
            entity.status = data.status().name();
            entity.batchId = data.batchId();
            entity.createdAt = data.createdAt();
            entity.updatedAt = data.updatedAt();
            entity.lastError = data.lastError();
            entity.providerMessageId = data.providerMessageId();
            entity.scheduledAt = data.scheduledAt();
            entity.processingToken = null;
            entity.processingStartedAt = null;
            return toData(repository.save(entity));
        } catch (Exception ex) {
            throw new IllegalStateException("unable to persist mail message", ex);
        }
    }

    @Override
    public Optional<MailMessageData> findById(UUID id) {
        return repository.findById(id).map(this::toData);
    }

    @Override
    public Optional<MailMessageData> findByClientIdAndIdempotencyKey(String clientId, String key) {
        return repository.findByClientIdAndIdempotencyKey(clientId, key).map(this::toData);
    }

    @Override
    public long countByBatchId(UUID batchId) {
        return repository.countByBatchId(batchId);
    }

    @Override
    public long countByBatchIdAndStatus(UUID batchId, MailStatus status) {
        return repository.countByBatchIdAndStatus(batchId, status.name());
    }

    private MailMessageData toData(MailMessageEntity entity) {
        try {
            return new MailMessageData(
                    entity.id,
                    entity.clientId,
                    entity.idempotencyKey,
                    entity.subject,
                    entity.templateKey,
                    json.readValue(entity.variablesJson, new TypeReference<Map<String, Object>>() {}),
                    json.readValue(entity.recipientsJson, new TypeReference<List<MailRecipient>>() {}),
                    json.readValue(entity.attachmentIdsJson, new TypeReference<List<UUID>>() {}),
                    MailPriority.valueOf(entity.priority),
                    MailStatus.valueOf(entity.status),
                    entity.batchId,
                    entity.createdAt,
                    entity.updatedAt,
                    entity.lastError,
                    entity.providerMessageId,
                    entity.scheduledAt);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to map mail message", ex);
        }
    }
}
