package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.api.service.SignedWebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {
    private final SignedWebhookService hooks;
    public WebhookController(SignedWebhookService hooks){this.hooks=hooks;}

    @PutMapping("/subscription")
    public Object subscribe(Principal principal,@Valid @RequestBody Request body){
        return hooks.configure(principal.getName(),body.endpoint());
    }
    @GetMapping("/subscription")
    public Object subscription(Principal principal){return hooks.summary(principal.getName());}
    @DeleteMapping("/subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(Principal principal){hooks.disable(principal.getName());}
    @GetMapping("/deliveries")
    public Object deliveries(Principal principal,@RequestParam(defaultValue="50") int limit){
        return hooks.deliveries(principal.getName(),limit);
    }
    public record Request(@NotBlank String endpoint){}
}
