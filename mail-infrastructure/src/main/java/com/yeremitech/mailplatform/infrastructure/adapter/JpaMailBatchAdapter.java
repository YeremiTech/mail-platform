package com.yeremitech.mailplatform.infrastructure.adapter;

import com.yeremitech.mailplatform.application.model.MailBatchData;
import com.yeremitech.mailplatform.application.port.MailBatchRepository;
import com.yeremitech.mailplatform.domain.BatchStatus;
import com.yeremitech.mailplatform.infrastructure.jpa.MailBatchEntity;
import com.yeremitech.mailplatform.infrastructure.jpa.SpringDataMailBatchRepository;
import java.util.Optional;
import java.util.UUID;

public final class JpaMailBatchAdapter implements MailBatchRepository {
    private final SpringDataMailBatchRepository repository;

    public JpaMailBatchAdapter(SpringDataMailBatchRepository repository) {
        this.repository = repository;
    }

    @Override
    public MailBatchData save(MailBatchData data) {
        MailBatchEntity entity = new MailBatchEntity();
        entity.id = data.id();
        entity.clientId = data.clientId();
        entity.subject = data.subject();
        entity.templateKey = data.templateKey();
        entity.totalRecipients = data.totalRecipients();
        entity.status = data.status().name();
        entity.createdAt = data.createdAt();
        entity.updatedAt = data.updatedAt();
        return toData(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<MailBatchData> findById(UUID id) {
        return repository.findById(id).map(this::toData);
    }

    private MailBatchData toData(MailBatchEntity entity) {
        return new MailBatchData(
                entity.id, entity.clientId, entity.subject, entity.templateKey,
                entity.totalRecipients, BatchStatus.valueOf(entity.status),
                entity.createdAt, entity.updatedAt);
    }
}
