package com.redis.workshop.config;

import com.redis.workshop.service.OpenAiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catches unhandled exceptions from controllers and returns a clean JSON
 * response instead of Spring's Whitelabel Error Page. Keeps the demo UX
 * predictable when a use case fails in front of an audience.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Bad request on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoHandlerFoundException ex, HttpServletRequest request) {
        log.warn("No handler for {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.NOT_FOUND, "No handler for " + request.getRequestURI(), request);
    }

    @ExceptionHandler(OpenAiException.class)
    public ResponseEntity<Map<String, Object>> handleOpenAi(
            OpenAiException ex, HttpServletRequest request) {
        log.error("OpenAI API failure on {}: status={} message={}",
                request.getRequestURI(), ex.getStatusCode(), ex.getMessage());
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage() != null ? ex.getMessage() : "OpenAI API error");
        body.put("status", status.value());
        body.put("path", request.getRequestURI());
        body.put("source", "openai");
        if (ex.getStatusCode() > 0) {
            body.put("openaiStatus", ex.getStatusCode());
        }
        if (ex.getResponseBody() != null && !ex.getResponseBody().isBlank()) {
            body.put("openaiResponse", ex.getResponseBody());
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(
            RuntimeException ex, HttpServletRequest request) {
        log.error("Runtime exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), request);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message != null ? message : status.getReasonPhrase());
        body.put("status", status.value());
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
