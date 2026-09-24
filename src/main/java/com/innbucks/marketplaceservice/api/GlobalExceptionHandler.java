package com.innbucks.marketplaceservice.api;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Renders every error as the fleet ApiResult envelope. Unhandled exceptions
 * become a generic 500 — internals (messages, stack traces) never reach the
 * client; they go to the log with the correlation id from MDC.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * {@code details} is null for almost every refusal, and {@link ApiResult} is
     * {@code @JsonInclude(NON_NULL)}, so those still render as the two-field
     * {@code {code, message}} body they always have. Only a refusal that
     * deliberately attaches a payload (order creation's per-line rejections)
     * gains a {@code data}.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResult<Object>> apiException(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ApiResult<>(ex.code(), ex.getMessage(), ex.details()));
    }

    /**
     * Method-level {@code @PreAuthorize} throws {@code AuthorizationDeniedException}
     * (an {@link AccessDeniedException}) from INSIDE the handler invocation, past
     * the filter chain — so SecurityConfig's accessDeniedHandler never sees it and,
     * without this mapping, the {@code Exception} catch-all below would convert a
     * role refusal into a 500. Same fix (and same rationale comment) as InnRewards'
     * fleet handler. Renders the identical envelope SecurityConfig's
     * accessDeniedHandler writes so callers see one 403 shape regardless of which
     * layer refused.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> accessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResult.error("FORBIDDEN", "Forbidden - insufficient role"));
    }

    /**
     * A lost optimistic lock (@Version) means two writers raced the same row —
     * e.g. a buyer cancel interleaving the expiry sweep on one order. That is
     * a retryable conflict, not a server fault: render 409, not the 500 the
     * catch-all would produce.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<Void>> optimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error("CONCURRENT_UPDATE", "The resource was modified concurrently - retry"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(new ApiResult<>("VALIDATION_ERROR", "Request validation failed", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResult.error("MALFORMED_REQUEST", "Request body is malformed"));
    }

    /**
     * The servlet multipart cap (spring.servlet.multipart.max-file-size, 10MB)
     * trips BEFORE the controller runs, so ListingService's own size check
     * never sees the request — without this mapping an oversized image upload
     * would fall into the catch-all below as a 500. Render the SAME 400
     * envelope the in-code guard produces so clients see one contract for
     * "too big" regardless of which layer refused.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResult.error("image_too_large",
                        "That image is too large. Please use one under 10 MB."));
    }

    /**
     * An unknown path is a 404, not a 500.
     *
     * <p>Without this, {@code NoResourceFoundException} falls through to the
     * catch-all below and every typo'd URL answers {@code 500 INTERNAL_ERROR}
     * — which tells a client its request was fine and our server broke, and
     * logs somebody else's typo at ERROR. A scanner walking paths could fill
     * the error log on its own.
     *
     * <p>Logged at DEBUG: an unmatched path is ordinary traffic, not a fault.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> noSuchPath(NoResourceFoundException ex) {
        log.debug("No handler for {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error("not_found", "Not found"));
    }

    /**
     * A query or path parameter the client sent in the wrong shape — a status
     * that is not one of the enum's values, a malformed UUID — is the client's
     * mistake, and says which parameter. Without this it fell into the
     * catch-all as a 500, telling the client our server broke over their typo
     * (several controllers had taken their ids as Strings purely to avoid it).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> parameterMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResult.error("invalid_parameter",
                        "'" + ex.getName() + "' has a value we cannot read"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> unhandled(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
