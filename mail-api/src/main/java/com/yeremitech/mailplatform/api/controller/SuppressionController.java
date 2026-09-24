package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.api.service.SuppressionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** The authenticated tenant records opt-outs received by its own application. */
@RestController
@RequestMapping("/api/v1/suppressions")
public class SuppressionController {
    private final SuppressionService service;
    public SuppressionController(SuppressionService service) { this.service=service; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Object suppress(Principal principal,@Valid @RequestBody Request body) {
        return service.suppress(principal.getName(),body.email(),body.reason());
    }
    @GetMapping public Object list(Principal principal,@RequestParam(defaultValue="50") int limit) {
        return service.recent(principal.getName(),limit);
    }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Principal principal,@RequestParam String email) {
        service.remove(principal.getName(),email);
    }
    public record Request(@NotBlank @Email String email,@NotNull SuppressionService.Reason reason) {}
}
