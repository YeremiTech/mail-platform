package com.yeremitech.mailplatform.api.service;

import com.yeremitech.mailplatform.application.usecase.RequestPasswordRecoveryUseCase;
import com.yeremitech.mailplatform.api.security.ClientDisplayNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordRecoveryRequestService {
    private final RequestPasswordRecoveryUseCase requestPasswordRecovery;
    private final ClientDisplayNames displayNames;

    public PasswordRecoveryRequestService(RequestPasswordRecoveryUseCase requestPasswordRecovery, ClientDisplayNames displayNames) {
        this.requestPasswordRecovery = requestPasswordRecovery;
        this.displayNames = displayNames;
    }

    @Transactional
    public RequestPasswordRecoveryUseCase.Result request(RequestPasswordRecoveryUseCase.Command command) {
        return requestPasswordRecovery.execute(command, displayNames.nameFor(command.clientId()));
    }
}
