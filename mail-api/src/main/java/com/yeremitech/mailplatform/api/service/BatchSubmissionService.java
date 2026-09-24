package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.application.usecase.CreateBatchUseCase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatchSubmissionService {
    private final CreateBatchUseCase createBatch;

    public BatchSubmissionService(CreateBatchUseCase createBatch) { this.createBatch = createBatch; }

    @Transactional
    public CreateBatchUseCase.Result submit(CreateBatchUseCase.Command command) {
        return createBatch.execute(command);
    }
}
