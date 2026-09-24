package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.application.usecase.ConsumeResetGrantUseCase;
import com.yeremitech.mailplatform.application.usecase.RequestPasswordRecoveryUseCase;
import com.yeremitech.mailplatform.application.usecase.VerifyPasswordRecoveryUseCase;
import com.yeremitech.mailplatform.api.service.PasswordRecoveryRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/password-recovery")
public class PasswordRecoveryController {
    private final PasswordRecoveryRequestService request;
    private final VerifyPasswordRecoveryUseCase verify;
    private final ConsumeResetGrantUseCase consume;
    public PasswordRecoveryController(PasswordRecoveryRequestService r, VerifyPasswordRecoveryUseCase v, ConsumeResetGrantUseCase c) { request=r; verify=v; consume=c; }

    @PostMapping("/challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Object request(Principal principal, @Valid @RequestBody RequestDto d) {
        return request.request(new RequestPasswordRecoveryUseCase.Command(principal.getName(), d.subjectReference(), d.email()));
    }
    @PostMapping("/challenges/{id}/verify") public Object verify(Principal principal, @PathVariable UUID id, @Valid @RequestBody VerifyDto d) { return verify.execute(principal.getName(),id,d.code()); }
    @PostMapping("/grants/consume") public Object consume(Principal principal, @Valid @RequestBody ConsumeDto d) { return consume.execute(principal.getName(),d.grantId(),d.resetGrant()); }

    public record RequestDto(@NotBlank @Size(max=200) String subjectReference, @Email @NotBlank @Size(max=320) String email) {}
    public record VerifyDto(@NotBlank @Pattern(regexp="\\d{6}") String code) {}
    public record ConsumeDto(@NotNull UUID grantId, @NotBlank String resetGrant) {}
}
