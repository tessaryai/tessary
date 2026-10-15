// SPDX-License-Identifier: Apache-2.0
package ai.tessary.web;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.Retryable;
import ai.tessary.open.errors.TessaryException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TessaryException.class)
    public ResponseEntity<ApiResponse<Void>> handleTessary(TessaryException ex) {
        HttpStatus status = ex.error().status();
        if (status.is5xxServerError()) {
            // 5xx TessaryExceptions are otherwise invisible; pass the cause last so its stack trace is logged.
            log.error("{} {}: {}", status.value(), ex.error().code(), ex.getMessage(), ex.getCause());
        } else {
            // 4xx stays DEBUG: egressing needs Markers.OPS, which `web_is_http_plumbing_only` bars here, so the raising
            // slice logs it.
            log.debug("{} {}: {}", status.value(), ex.error().code(), ex.getMessage());
        }
        ResponseEntity<ApiResponse<Void>> response = build(ex.error(), ex.getMessage(), null);
        if (ex instanceof Retryable retryable) {
            return ResponseEntity.status(response.getStatusCode())
                    .header(HttpHeaders.RETRY_AFTER, Integer.toString(retryable.retryAfterSeconds()))
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        // VALIDATION_FAILED's template carries a %s, so it must always be rendered with an arg.
        String summary = details.isEmpty()
                ? "invalid request"
                : details.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining("; "));
        return build(CommonError.VALIDATION_FAILED, CommonError.VALIDATION_FAILED.render(summary), details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadJson(HttpMessageNotReadableException ex) {
        String msg = ex.getMostSpecificCause().getMessage();
        return build(CommonError.INVALID_BODY, CommonError.INVALID_BODY.render(msg), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException ex) {
        return build(CommonError.INVALID_PARAMETER, CommonError.INVALID_PARAMETER.render(ex.getMessage()), null);
    }

    // Spring's own binding failures for a missing query parameter, a missing multipart part or an
    // unconvertible path variable used to fall through to the 500 fallback below, so a callback hit
    // without its `code`, an import with no `files` part, or a provider path segment that is not an
    // enum constant, answered "internal server error" for what is a bad request.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return build(
                CommonError.INVALID_PARAMETER,
                CommonError.INVALID_PARAMETER.render("'" + ex.getParameterName() + "' is required"),
                null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(
                CommonError.INVALID_PARAMETER,
                CommonError.INVALID_PARAMETER.render("'" + ex.getName() + "' has an unrecognised value"),
                null);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        return build(
                CommonError.INVALID_PARAMETER,
                CommonError.INVALID_PARAMETER.render("multipart part '" + ex.getRequestPartName() + "' is required"),
                null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        MediaType contentType = ex.getContentType();
        String given = contentType != null ? contentType.toString() : "none";
        return build(CommonError.UNSUPPORTED_MEDIA_TYPE, CommonError.UNSUPPORTED_MEDIA_TYPE.render(given), null);
    }

    /**
     * Spring MVC raises this, not {@code NoHandlerFoundException}, once resource handling is registered; without it a
     * typo'd path answered 500.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return build(CommonError.NOT_FOUND, "No handler for " + ex.getHttpMethod() + " /" + ex.getResourcePath(), null);
    }

    /** Without it a wrong verb answered 500 rather than 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadMethod(HttpRequestMethodNotSupportedException ex) {
        ErrorBody body = new ErrorBody(
                CommonError.NOT_FOUND.code(), "method " + ex.getMethod() + " is not supported on this path", null);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.failure(HttpStatus.METHOD_NOT_ALLOWED.value(), body));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
        long maxBytes = ex.getMaxUploadSize(); // -1 if not known to the resolver
        Throwable cause = ex.getCause();
        String causeMsg = cause != null ? cause.getMessage() : null;
        // Visible without enabling DEBUG.
        log.warn("multipart rejected: configured-max-bytes={} cause={}", maxBytes, causeMsg);

        String detail = causeMsg != null
                ? causeMsg // most informative: "size N exceeds maximum M"
                : maxBytes > 0
                        ? "upload exceeded the per-request limit of " + (maxBytes / (1024 * 1024)) + " MB; "
                                + "raise spring.servlet.multipart.max-request-size or split the upload"
                        : ex.getMessage();
        return build(CommonError.PAYLOAD_TOO_LARGE, CommonError.PAYLOAD_TOO_LARGE.render(detail), null);
    }

    /**
     * For 5xx the reason is discarded, since a wrapped message may carry SQL or internal state; 4xx reasons are static
     * developer text.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            log.error(
                    "ResponseStatusException 5xx on {} {}: {}: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    ex.getClass().getName(),
                    ex.getReason(),
                    ex);
            ErrorBody body = new ErrorBody(CommonError.INTERNAL.code(), CommonError.INTERNAL.render(), null);
            return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), body));
        }
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        ErrorBody body = new ErrorBody(status.name(), reason, null);
        return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), body));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleFallback(Exception ex, HttpServletRequest request) {
        // Coordinates and the exception's type and message lead because only the body is indexed in Loki; ex last keeps
        // the stack trace.
        log.error(
                "unhandled exception on {} {}: {}: {}",
                request.getMethod(),
                request.getRequestURI(),
                ex.getClass().getName(),
                ex.getMessage(),
                ex);
        return build(CommonError.INTERNAL, CommonError.INTERNAL.render(), null);
    }

    private static ResponseEntity<ApiResponse<Void>> build(
            ErrorCode code, @Nullable String message, @Nullable Map<String, String> details) {
        ErrorBody body = new ErrorBody(code.code(), message, details);
        ApiResponse<Void> envelope = ApiResponse.failure(code.status().value(), body);
        return ResponseEntity.status(code.status()).body(envelope);
    }
}
