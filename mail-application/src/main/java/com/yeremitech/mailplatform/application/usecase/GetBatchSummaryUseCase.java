package com.yeremitech.mailplatform.application.usecase;

import com.yeremitech.mailplatform.application.model.MailBatchData;
import com.yeremitech.mailplatform.application.port.MailBatchRepository;
import com.yeremitech.mailplatform.application.port.MailMessageRepository;
import com.yeremitech.mailplatform.domain.MailStatus;
import com.yeremitech.mailplatform.domain.BatchStatus;
import java.util.UUID;
import java.util.NoSuchElementException;

public final class GetBatchSummaryUseCase {
    private final MailBatchRepository batchRepository;
    private final MailMessageRepository mailRepository;

    public GetBatchSummaryUseCase(MailBatchRepository batchRepository, MailMessageRepository mailRepository) {
        this.batchRepository = batchRepository;
        this.mailRepository = mailRepository;
    }

    public Result execute(UUID batchId) {
        MailBatchData batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("batch not found"));
        long messages = mailRepository.countByBatchId(batchId);
        long sent = mailRepository.countByBatchIdAndStatus(batchId, MailStatus.SENT);
        long failed = mailRepository.countByBatchIdAndStatus(batchId, MailStatus.FAILED);
        long queued = mailRepository.countByBatchIdAndStatus(batchId, MailStatus.QUEUED);
        long processing = mailRepository.countByBatchIdAndStatus(batchId, MailStatus.PROCESSING);
        long retrying = mailRepository.countByBatchIdAndStatus(batchId, MailStatus.RETRYING);
        long cancelled = mailRepository.countByBatchIdAndStatus(batchId, MailStatus.CANCELLED);
        BatchStatus status = batch.status() == BatchStatus.CANCELLED && queued + processing + retrying == 0
                ? BatchStatus.CANCELLED
                : cancelled > 0 && queued + processing + retrying == 0 ? BatchStatus.CANCELLED
                : messages == batch.totalRecipients() && sent == messages ? BatchStatus.COMPLETED
                : messages == batch.totalRecipients() && failed == messages ? BatchStatus.FAILED
                : messages == batch.totalRecipients() && sent + failed == messages ? BatchStatus.PARTIALLY_COMPLETED
                : processing > 0 || retrying > 0 ? BatchStatus.PROCESSING : BatchStatus.QUEUED;
        MailBatchData current = new MailBatchData(batch.id(), batch.clientId(), batch.subject(), batch.templateKey(),
                batch.totalRecipients(), status, batch.createdAt(), batch.updatedAt());
        return new Result(current, messages, sent, failed, queued, processing, retrying, cancelled);
    }

    public record Result(
            MailBatchData batch,
            long messages,
            long sent,
            long failed,
            long queued,
            long processing,
            long retrying,
            long cancelled) {
    }
}
