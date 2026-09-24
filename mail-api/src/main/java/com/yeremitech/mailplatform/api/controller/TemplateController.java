package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.api.service.DynamicTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {
    private final DynamicTemplateService templates;
    public TemplateController(DynamicTemplateService templates) { this.templates=templates; }

    @GetMapping
    public Object list(Principal principal) { return templates.list(principal.getName()); }

    @PostMapping("/{slug}")
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(Principal principal, @PathVariable String slug, @Valid @RequestBody TemplateBody body) {
        return templates.create(principal.getName(), slug, toBody(body));
    }

    @PostMapping("/{slug}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public Object revise(Principal principal, @PathVariable String slug, @Valid @RequestBody TemplateBody body) {
        return templates.revise(principal.getName(), slug, toBody(body));
    }

    @GetMapping("/{slug}/versions")
    public Object versions(Principal principal, @PathVariable String slug) {
        return templates.versions(principal.getName(), slug);
    }

    @PostMapping("/{slug}/versions/{version}/publish")
    public Object publish(Principal principal, @PathVariable String slug, @PathVariable int version) {
        return templates.publish(principal.getName(), slug, version);
    }

    @PostMapping("/{slug}/versions/{version}/preview")
    public Object preview(Principal principal, @PathVariable String slug, @PathVariable int version,
                          @RequestBody(required=false) Map<String,Object> variables) {
        return templates.preview(principal.getName(), slug, version, variables);
    }

    @DeleteMapping("/{slug}/publication")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpublish(Principal principal, @PathVariable String slug) {
        templates.unpublish(principal.getName(), slug);
    }

    public record TemplateBody(@NotBlank @Size(max=131072) String html,
            @NotBlank @Size(max=65536) String text,
            @NotNull @Size(max=50) List<@NotBlank String> requiredVariables) {}

    private static DynamicTemplateService.Body toBody(TemplateBody b) {
        return new DynamicTemplateService.Body(b.html(), b.text(), b.requiredVariables());
    }
}
