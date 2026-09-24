package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.api.service.UnsubscribeLinkService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET never changes subscription state: mail scanners may prefetch all links. */
@RestController
@SecurityRequirements
@RequestMapping("/api/v1/public/unsubscribe")
public class PublicUnsubscribeController {
    private static final String PAGE = """
            <!doctype html><html lang="es"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="robots" content="noindex,nofollow">
            <title>Cancelar suscripción</title></head>
            <body><main><h1>Cancelar suscripción</h1>
            <p>Confirma para dejar de recibir correos comerciales de este remitente.</p>
            <form action="/api/v1/public/unsubscribe" method="post">
            <input type="hidden" name="token" value="%s">
            <button type="submit">Confirmar baja</button></form></main></body></html>
            """;
    private static final String RESULT = """
            <!doctype html><html lang="es"><head><meta charset="UTF-8">
            <meta name="robots" content="noindex,nofollow"><title>Solicitud procesada</title></head>
            <body><main><h1>Solicitud procesada</h1>
            <p>Si el enlace era válido, tu baja de correos comerciales está registrada.</p>
            </main></body></html>
            """;
    private final UnsubscribeLinkService service;
    public PublicUnsubscribeController(UnsubscribeLinkService service) { this.service=service; }

    @GetMapping(produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> confirmation(@RequestParam(defaultValue="") String token) {
        String safe = com.yeremitech.mailplatform.domain.UnsubscribeToken.wellFormed(token) ? token : "";
        return page(PAGE.formatted(safe));
    }

    @PostMapping(consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@RequestParam(defaultValue="") String token) {
        service.consume(token);
        return page(RESULT);
    }

    /** RFC 8058 POST requires the explicit One-Click form field. GET never unsubscribes. */
    @PostMapping(path="/one-click", consumes={MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            MediaType.MULTIPART_FORM_DATA_VALUE},
            produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oneClick(@RequestParam(defaultValue="") String token,
            @RequestParam(name="List-Unsubscribe", defaultValue="") String confirmation) {
        if (!"One-Click".equals(confirmation))
            throw new IllegalArgumentException("invalid one-click unsubscribe request");
        service.consume(token);
        return page(RESULT);
    }

    @GetMapping(path="/one-click", produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oneClickPreview(@RequestParam(defaultValue="") String token) {
        return confirmation(token);
    }

    private static ResponseEntity<String> page(String html) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy","no-referrer")
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
                .header("X-Frame-Options","DENY")
                .body(html);
    }
}
