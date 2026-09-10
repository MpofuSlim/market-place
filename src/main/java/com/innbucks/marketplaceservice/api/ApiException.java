package com.innbucks.marketplaceservice.api;

import org.springframework.http.HttpStatus;

/**
 * Domain error carrying the HTTP status + stable machine-readable code that
 * {@link GlobalExceptionHandler} renders into the ApiResult envelope. Messages
 * must be safe to show a client — internals stay in logs.
 *
 * <p><b>{@code details} is optional and additive.</b> It rides in the
 * envelope's {@code data}, which is {@code @JsonInclude(NON_NULL)} — so every
 * error that does not set it renders byte-for-byte as it always did. It exists
 * for refusals a client must ACT on line by line (order creation naming every
 * unavailable listing at once, rather than the first). Keep it to safe,
 * client-facing values: it is serialized straight to the caller.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Extra machine-readable context for the caller, or null (the usual case). */
    public Object details() {
        return details;
    }

    /**
     * This same refusal, carrying {@code details}. Used where the status, code
     * and message must stay exactly what they were for existing clients while
     * a richer payload is offered to new ones.
     */
    public ApiException withDetails(Object payload) {
        return new ApiException(status, code, getMessage(), payload);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        // UNPROCESSABLE_CONTENT is RFC 9110's name for 422; the old
        // UNPROCESSABLE_ENTITY constant is deprecated in Spring Framework 7.
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
