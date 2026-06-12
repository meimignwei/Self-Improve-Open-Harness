package com.openharness.api;

import java.time.Instant;

/**
 * Sealed class hierarchy for typed API errors.
 * Java equivalent of Python's error classes.
 */
public sealed abstract class ApiError extends RuntimeException
        permits ApiError.AuthError, ApiError.RateLimitError,
                ApiError.RequestError, ApiError.ServerError {

    private final int statusCode;

    ApiError(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }

    /** 401 Unauthorized */
    public static final class AuthError extends ApiError {
        public AuthError(int statusCode, String message) {
            super(statusCode, message);
        }
    }

    /** 429 Too Many Requests */
    public static final class RateLimitError extends ApiError {
        private final Instant retryAfter;

        public RateLimitError(int statusCode, String message, Instant retryAfter) {
            super(statusCode, message);
            this.retryAfter = retryAfter;
        }

        public Instant retryAfter() { return retryAfter; }
    }

    /** 400/422 Bad Request */
    public static final class RequestError extends ApiError {
        public RequestError(int statusCode, String message) {
            super(statusCode, message);
        }
    }

    /** 500/502/503 Server Error */
    public static final class ServerError extends ApiError {
        public ServerError(int statusCode, String message) {
            super(statusCode, message);
        }
    }
}
