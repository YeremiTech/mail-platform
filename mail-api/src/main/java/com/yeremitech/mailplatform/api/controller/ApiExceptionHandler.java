package com.yeremitech.mailplatform.api.controller;

import com.yeremitech.mailplatform.application.error.RateLimitExceededException;
import com.yeremitech.mailplatform.api.service.AttachmentMalwareScanner;
import com.yeremitech.mailplatform.api.service.ClamAvInstream;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.security.access.AccessDeniedException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ClamAvInstream.MalwareDetectedException.class)
    ResponseEntity<?> infected(ClamAvInstream.MalwareDetectedException e) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "INFECTED_ATTACHMENT", "Attachment rejected by antivirus");
    }

    @ExceptionHandler(AttachmentMalwareScanner.ScannerUnavailableException.class)
    ResponseEntity<?> antivirusUnavailable(AttachmentMalwareScanner.ScannerUnavailableException e) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "ANTIVIRUS_UNAVAILABLE", "Attachment scanner unavailable");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return response(HttpStatus.BAD_REQUEST, "REQUEST_REJECTED", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> conflict(IllegalStateException e) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<?> notFound(NoSuchElementException e) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<?> rateLimit(RateLimitExceededException e) {
        return response(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> forbidden(AccessDeniedException e) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(Exception e) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body, header or file is invalid");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> unsupportedType(HttpMediaTypeNotSupportedException e) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported request content type");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<?> unacceptableType(HttpMediaTypeNotAcceptableException e) {
        return response(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "Requested response content type is unavailable");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> unsupportedMethod(HttpRequestMethodNotSupportedException e) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method is not supported");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> noRoute(NoResourceFoundException e) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Uploaded file exceeds the size limit");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception e) {
        log.error("Unhandled API failure", e);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected internal error");
    }

    private ResponseEntity<?> response(HttpStatus status, String code, String message) {
        Map<String,Object> payload = new java.util.LinkedHashMap<>();
        payload.put("timestamp", Instant.now());
        payload.put("code", code);
        payload.put("message", message == null ? status.getReasonPhrase() : message);
        if (MDC.get("requestId") != null) payload.put("requestId", MDC.get("requestId"));
        return ResponseEntity.status(status).body(payload);
    }
}
