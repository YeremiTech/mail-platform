package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.application.port.AttachmentStoragePort;
import com.yeremitech.mailplatform.api.service.AttachmentSignatureVerifier;
import com.yeremitech.mailplatform.api.service.AttachmentMalwareScanner;
import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {
    private static final long MAX_FILE_SIZE = 15L * 1024 * 1024;
    private static final Map<String,Set<String>> MIME_EXTENSIONS = Map.of(
            "application/pdf", Set.of("pdf"), "image/png", Set.of("png"),
            "image/jpeg", Set.of("jpg", "jpeg"), "image/webp", Set.of("webp"),
            "text/plain", Set.of("txt"), "text/csv", Set.of("csv"),
            "application/xml", Set.of("xml"), "text/xml", Set.of("xml"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx"),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx"));
    private final AttachmentStoragePort storage;
    private final AttachmentMalwareScanner scanner;
    public AttachmentController(AttachmentStoragePort storage, AttachmentMalwareScanner scanner) {
        this.storage = storage; this.scanner = scanner;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Object upload(Principal principal, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("file is empty");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("file exceeds 15 MB");
        String contentType = file.getContentType() == null ? "application/octet-stream"
                : file.getContentType().split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        Set<String> extensions = MIME_EXTENSIONS.get(contentType);
        if (extensions == null) throw new IllegalArgumentException("attachment content type is not allowed");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\','/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (filename.isBlank() || filename.length() > 255 || filename.matches(".*[\\p{Cntrl}].*")) {
            throw new IllegalArgumentException("invalid attachment filename");
        }
        int lastDot = filename.lastIndexOf('.');
        String extension = lastDot < 0 ? "" : filename.substring(lastDot+1).toLowerCase(Locale.ROOT);
        if (!extensions.contains(extension)) throw new IllegalArgumentException("file extension does not match declared MIME type");
        // Multipart is disk-backed. Reopen streams for validation, scanning and storage; do not call getBytes().
        try (InputStream content = file.getInputStream()) {
            AttachmentSignatureVerifier.verify(contentType, content, file.getSize());
        }
        try (InputStream content = file.getInputStream()) {
            scanner.scan(content, file.getSize());
        }
        try (InputStream content = file.getInputStream()) {
            return storage.store(principal.getName(), filename, contentType, content, file.getSize());
        }
    }
}
