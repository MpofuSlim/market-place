package com.innbucks.marketplaceservice.api;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    // ---------------------------------------------------------------------
    // Spring MVC's own client errors. A @RestControllerAdvice runs BEFORE
    // Spring's DefaultHandlerExceptionResolver, so without these every one of
    // them fell into the Exception catch-all below: a wrong verb, a wrong
    // Content-Type or a missing multipart part answered 500 INTERNAL_ERROR
    // and logged a stack trace at ERROR (which Sentry turns into an alert).
    // Each keeps its NATIVE status and headers (Allow on a 405, Accept on a
    // 415). Messages are ours and name only things our code declares (a
    // parameter / part / header name) — never a class name or client input.
    // Logged at WARN (an integration mistake a developer should be able to
    // find) or DEBUG (405: what a path scanner produces), never ERROR.
    // ---------------------------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        log.debug("Method not allowed -> 405");
        return frameworkError(ex, "method_not_allowed",
                "That HTTP method is not supported on this path");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        logClientError(ex, null);
        return frameworkError(ex, "unsupported_media_type",
                "That Content-Type is not supported on this path");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResult<Void>> notAcceptable(HttpMediaTypeNotAcceptableException ex) {
        logClientError(ex, null);
        return frameworkError(ex, "not_acceptable",
                "We cannot respond in the format the Accept header asks for");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> missingParameter(MissingServletRequestParameterException ex) {
        logClientError(ex, "parameter " + ex.getParameterName());
        return frameworkError(ex, "missing_parameter",
                "Required parameter '" + ex.getParameterName() + "' is missing");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResult<Void>> missingPart(MissingServletRequestPartException ex) {
        logClientError(ex, "part " + ex.getRequestPartName());
        return frameworkError(ex, "missing_part",
                "Required part '" + ex.getRequestPartName() + "' is missing");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResult<Void>> missingHeader(MissingRequestHeaderException ex) {
        logClientError(ex, "header " + ex.getHeaderName());
        return frameworkError(ex, "missing_header",
                "Required header '" + ex.getHeaderName() + "' is missing");
    }

    /**
     * Built-in method validation (a constraint such as {@code @Min(1)} on a
     * {@code @RequestParam} / {@code @PathVariable}) is the same kind of
     * failure as a {@code @Valid} body, so it answers with the SAME contract:
     * 400 {@code VALIDATION_ERROR} with a name-to-message map — never a second,
     * vaguer code for one kind of mistake. Names are what the CLIENT sent (the
     * annotation's name, or a body's field path), first message per name wins
     * like the body handler. A failed RETURN-value validation reports 5xx and
     * is our fault, so it keeps the server-fault route.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<?> methodValidation(HandlerMethodValidationException ex) {
        if (!ex.getStatusCode().is4xxClientError()) {
            return unhandled(ex);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String paramName = requestName(result.getMethodParameter());
            if (result instanceof ParameterErrors errors) {
                for (var error : errors.getAllErrors()) {
                    String name = error instanceof FieldError fe ? fe.getField() : paramName;
                    fields.putIfAbsent(name, error.getDefaultMessage());
                }
            } else {
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    fields.putIfAbsent(paramName, error.getDefaultMessage());
                }
            }
        }
        logClientError(ex, null);
        return ResponseEntity.badRequest()
                .body(new ApiResult<>("VALIDATION_ERROR", "Request validation failed", fields));
    }

    /** The name the client used for a parameter, not the Java variable name. */
    private static String requestName(MethodParameter parameter) {
        String declared = null;
        RequestParam rp = parameter.getParameterAnnotation(RequestParam.class);
        PathVariable pv = parameter.getParameterAnnotation(PathVariable.class);
        RequestHeader rh = parameter.getParameterAnnotation(RequestHeader.class);
        RequestPart part = parameter.getParameterAnnotation(RequestPart.class);
        if (rp != null) {
            declared = rp.name();
        } else if (pv != null) {
            declared = pv.name();
        } else if (rh != null) {
            declared = rh.name();
        } else if (part != null) {
            declared = part.name();
        }
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        String javaName = parameter.getParameterName();
        return javaName != null ? javaName : "arg" + parameter.getParameterIndex();
    }

    /**
     * A multipart request we could not read — a {@code multipart/form-data}
     * Content-Type with no boundary, a truncated body, a file sent to a
     * {@code @RequestParam MultipartFile} on a non-multipart request. Spring
     * throws a plain {@link MultipartException}, which is NOT an
     * {@link ErrorResponse}, so the catch-all would have 500'd it at ERROR.
     * It is the multipart form of a malformed body and takes that contract.
     * {@link MaxUploadSizeExceededException} is a subclass with its own, more
     * specific handler above, so {@code image_too_large} is unaffected.
     * Logged at WARN with the root cause's CLASS — the rare disk-side failure
     * (temp dir full) would show there without echoing request data.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResult<Void>> malformedMultipart(MultipartException ex) {
        Throwable root = ex.getMostSpecificCause();
        logClientError(ex, root == ex ? null : "cause " + root.getClass().getSimpleName());
        return ResponseEntity.badRequest()
                .body(ApiResult.error("MALFORMED_REQUEST", "Request body is malformed"));
    }

    /**
     * The catch-all. Anything Spring itself classifies as a CLIENT error (its
     * {@link ErrorResponse} contract with a 4xx status — a future framework
     * exception included, e.g. {@code HandlerMethodValidationException} or a
     * {@code ResponseStatusException(4xx)}) keeps its status and headers under
     * a stable code from {@link #clientErrorCode}. Everything else — including
     * an {@code ErrorResponse} that says 5xx — is a server fault: generic 500,
     * internals only in the ERROR log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> unhandled(Exception ex) {
        if (ex instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            logClientError(ex, null);
            int status = er.getStatusCode().value();
            return frameworkError(er, clientErrorCode(status), clientErrorMessage(status));
        }
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    /**
     * Stable codes for the generic path — a fixed table, NOT derived from
     * {@code HttpStatus.name()}, which Spring has renamed across majors
     * (413 PAYLOAD_TOO_LARGE became CONTENT_TOO_LARGE) and would silently
     * change a published code.
     */
    static String clientErrorCode(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 409 -> "conflict";
            case 413 -> "request_too_large";
            case 415 -> "unsupported_media_type";
            default -> "request_rejected";
        };
    }

    static String clientErrorMessage(int status) {
        return switch (status) {
            case 400 -> "The request could not be processed as sent";
            case 404 -> "Not found";
            case 405 -> "That HTTP method is not supported on this path";
            case 406 -> "We cannot respond in the format the Accept header asks for";
            case 409 -> "The request conflicts with the current state";
            case 413 -> "The request is too large";
            case 415 -> "That Content-Type is not supported on this path";
            default -> "The request was rejected";
        };
    }

    /**
     * Native status + the framework's own headers (Allow, Accept,
     * Accept-Patch), with the Content-Type PINNED to JSON: on a 406 the
     * client's Accept header is by definition one we cannot satisfy, and
     * leaving the envelope to content negotiation would fail a second time
     * and lose the body.
     */
    private static ResponseEntity<ApiResult<Void>> frameworkError(
            ErrorResponse er, String code, String message) {
        HttpStatusCode status = er.getStatusCode();
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(er.getHeaders());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(status).headers(headers).body(ApiResult.error(code, message));
    }

    /**
     * One line, no stack trace: a client error is not a fault in this service.
     * Never {@code ex.getMessage()} — for several framework exceptions it
     * carries request data (every query value, the raw Content-Type), which
     * could put a full MSISDN in the log. Only the class, the status and a
     * name OUR code declares.
     */
    private static void logClientError(Exception ex, String declaredName) {
        int status = ex instanceof ErrorResponse er ? er.getStatusCode().value() : 400;
        if (declaredName == null) {
            log.warn("Client error rejected by the framework: {} -> {}",
                    ex.getClass().getSimpleName(), status);
        } else {
            log.warn("Client error rejected by the framework: {} ({}) -> {}",
                    ex.getClass().getSimpleName(), declaredName, status);
        }
    }
}
