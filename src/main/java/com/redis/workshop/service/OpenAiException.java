package com.redis.workshop.service;

/**
 * Typed exception for OpenAI API failures. Carries the HTTP status code and
 * raw response body from OpenAI so the GlobalExceptionHandler can surface
 * meaningful errors to the frontend without leaking stack traces.
 */
public class OpenAiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final boolean retryable;

    public OpenAiException(String message) {
        super(message);
        this.statusCode = 0;
        this.responseBody = null;
        this.retryable = false;
    }

    public OpenAiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
        this.retryable = false;
    }

    public OpenAiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
        this.retryable = retryable;
    }

    public OpenAiException(int statusCode, String responseBody, String message) {
        this(statusCode, responseBody, message, false);
    }

    public OpenAiException(int statusCode, String responseBody, String message, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.retryable = retryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
