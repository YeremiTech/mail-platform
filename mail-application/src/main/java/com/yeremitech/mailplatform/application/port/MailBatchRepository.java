package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.MailBatchData;
import java.util.Optional;
import java.util.UUID;

public interface MailBatchRepository {
    MailBatchData save(MailBatchData batch);
    Optional<MailBatchData> findById(UUID id);
}
