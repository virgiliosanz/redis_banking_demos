package com.redis.workshop.service;

/**
 * Typed exception for AMS (Agent Memory Server) failures. Carries the HTTP status
 * and raw response body so {@code GlobalExceptionHandler} can surface a clean,
 * demo-friendly error payload without leaking stack traces.
 *
 * A {@code statusCode} of 0 means the failure happened before an HTTP response
 * was received (e.g. connection refused, timeout).
 */
public class AmsException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public AmsException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }

    public AmsException(int statusCode, String responseBody, String message) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }

    /** True when the failure happened before AMS responded (connection / timeout). */
    public boolean isUnreachable() {
        return statusCode == 0;
    }
}
