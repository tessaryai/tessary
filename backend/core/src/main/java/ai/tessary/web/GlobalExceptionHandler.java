// SPDX-License-Identifier: Apache-2.0
package ai.tessary.web;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.TessaryException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TessaryException.class)
    public ResponseEntity<ApiResponse<Void>> handleTessary(TessaryException ex) {
        HttpStatus status = ex.error().status();
        if (status.is5xxServerError()) {
            // 5xx TessaryExceptions (upstream failures, internal errors) are otherwise
            // invisible: the envelope is built and returned with no log line, so a
            // 502 from an ingestion source only ever surfaces in the proxy access log.
            // Pass the cause last so the stack trace is logged when one exists.
            log.error("{} {}: {}", status.value(), ex.error().code(), ex.getMessage(), ex.getCause());
        } else {
            // NOTE: 4xx stays DEBUG here, i.e. invisible in prod (APP_LOG_LEVEL=INFO). Egressing it
            // needs Markers.OPS, and `web_is_http_plumbing_only` bars web/ from open.obs — so a
            // 4xx worth operational attention is logged by the slice that raises it (see
            // RcaController), not centrally. Revisit only by widening that rule deliberately.
            log.debug("{} {}: {}", status.value(), ex.error().code(), ex.getMessage());
        }
        return build(ex.error(), ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        // Summarize the field errors into the top-level message too; the per-field
        // breakdown stays in `details`. VALIDATION_FAILED's template carries a %s,
        // so it must always be rendered with an arg (never render() bare).
        String summary = details.isEmpty()
                ? "invalid request"
                : details.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue())
                        .collect(Collectors.joining("; "));
        return build(CommonError.VALIDATION_FAILED, CommonError.VALIDATION_FAILED.render(summary), details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadJson(HttpMessageNotReadableException ex) {
        String msg =
                ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        return build(CommonError.INVALID_BODY, CommonError.INVALID_BODY.render(msg), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException ex) {
        return build(CommonError.INVALID_PARAMETER, CommonError.INVALID_PARAMETER.render(ex.getMessage()), null);
    }

    // Spring's own binding failures for a missing query parameter, a missing multipart part or an
    // unconvertible path variable used to fall through to the 500 fallback below, so a callback hit
    // without its `code`, an import with no `files` part, or a provider path segment that is not an
    // enum constant, answered "internal server error" for what is a bad request. Epic 6's exposure
    // sweep (#1153) found this class on a fresh boot.
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

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return build(CommonError.NOT_FOUND, "No handler for " + ex.getHttpMethod() + " " + ex.getRequestURL(), null);
    }

    /**
     * An unmatched path that fell through to the static-resource handler. Spring MVC raises this rather
     * than {@link NoHandlerFoundException} once resource handling is registered, so without it a typo'd
     * {@code /api/...} path answered {@code 500 COMMON.INTERNAL} and logged an unhandled exception —
     * a wrong status on the programmatic surface plus an error line per client mistake.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return build(CommonError.NOT_FOUND, "No handler for " + ex.getHttpMethod() + " /" + ex.getResourcePath(), null);
    }

    /**
     * A path that exists at another verb. Same reason as the resource handler above: an integration that
     * PUTs where the API expects POST got {@code 500 COMMON.INTERNAL} and an error log, rather than the
     * {@code 405} that tells it what to change.
     */
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
        // Log loud so the configured limit + the underlying cause's byte counts
        // are both visible without enabling DEBUG. Cheap to keep on permanently.
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
     * Routes {@link ResponseStatusException} into the canonical {@link ApiResponse}
     * envelope so the wire shape stays consistent. For 5xx, the supplied reason
     * is discarded — if a caller wraps a service exception in
     * {@code new ResponseStatusException(500, e.getMessage())}, the underlying
     * message (which may include SQL fragments or internal state) must not reach
     * the client. For 4xx, the reason is developer-supplied static text and is
     * surfaced as-is.
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
        // Lead with the request coordinates and the exception's own type + message so
        // the log line is self-describing in Loki/Grafana (the body is all that's
        // indexed there). Pass ex last so the full stack trace and cause chain are
        // still rendered to the console and shipped as exception.* attributes.
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
