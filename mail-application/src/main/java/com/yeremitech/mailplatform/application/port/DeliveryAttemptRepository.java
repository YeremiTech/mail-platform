package com.yeremitech.mailplatform.application.port;

import com.yeremitech.mailplatform.application.model.DeliveryAttemptData;
import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository {
    int nextAttemptNumber(UUID messageId);
    DeliveryAttemptData save(DeliveryAttemptData attempt);
    List<DeliveryAttemptData> findByMessageId(UUID messageId);
}
