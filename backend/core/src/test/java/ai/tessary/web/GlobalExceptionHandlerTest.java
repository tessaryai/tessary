// SPDX-License-Identifier: Apache-2.0
package ai.tessary.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link GlobalExceptionHandler}: each exception a request can raise answers its own status and the
 * canonical error envelope, never the 500 fallback for a client mistake and never an internal message on
 * a 5xx.
 */
class GlobalExceptionHandlerTest {

    private static final MockHttpServletRequest REQUEST = new MockHttpServletRequest("POST", "/api/v1/things");

    private static MethodParameter anyParameter() {
        try {
            return new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    static Stream<Arguments> cases() {
        BeanPropertyBindingResult classLevelOnly = new BeanPropertyBindingResult(new Object(), "req");
        classLevelOnly.reject("range", "from must precede to");
        BeanPropertyBindingResult oneField = new BeanPropertyBindingResult(new Object(), "req");
        oneField.addError(new FieldError("req", "name", "must not be blank"));
        MaxUploadSizeExceededException unknownLimit = new MaxUploadSizeExceededException(-1);
        return Stream.of(
                row(
                        "an upstream 5xx TessaryException answers its own status, not a generic 500",
                        h -> h.handleTessary(new TessaryException(GitError.PROVIDER_CALL_FAILED, "GitHub", "timeout")),
                        502,
                        "GIT.PROVIDER_CALL_FAILED",
                        "GitHub API call failed: timeout",
                        null),
                row(
                        "a 4xx TessaryException answers its own status and code",
                        h -> h.handleTessary(new TessaryException(CommonError.NOT_FOUND)),
                        404,
                        "COMMON.NOT_FOUND",
                        "Resource not found",
                        null),
                row(
                        "a field validation failure names the field in the message and in details",
                        h -> h.handleValidation(new MethodArgumentNotValidException(anyParameter(), oneField)),
                        400,
                        "COMMON.VALIDATION_FAILED",
                        "Request validation failed: name must not be blank",
                        Map.of("name", "must not be blank")),
                row(
                        "a class-level validation failure with no field errors still says something",
                        h -> h.handleValidation(new MethodArgumentNotValidException(anyParameter(), classLevelOnly)),
                        400,
                        "COMMON.VALIDATION_FAILED",
                        "Request validation failed: invalid request",
                        Map.of()),
                row(
                        "malformed JSON reports the parser's own detail",
                        h -> h.handleBadJson(new HttpMessageNotReadableException(
                                "JSON parse error",
                                new IllegalArgumentException("Unexpected character ('}')"),
                                new MockHttpInputMessage(new byte[0]))),
                        400,
                        "COMMON.INVALID_BODY",
                        "Malformed request body: Unexpected character ('}')",
                        null),
                row(
                        "an IllegalArgumentException is a 400, not a 500",
                        h -> h.handleIllegalArg(new IllegalArgumentException("limit must be positive")),
                        400,
                        "COMMON.INVALID_PARAMETER",
                        "Invalid parameter: limit must be positive",
                        null),
                row(
                        "a missing query parameter is a 400 naming it",
                        h -> h.handleMissingParameter(new MissingServletRequestParameterException("code", "String")),
                        400,
                        "COMMON.INVALID_PARAMETER",
                        "Invalid parameter: 'code' is required",
                        null),
                row(
                        "an unconvertible path variable is a 400 naming it",
                        h -> h.handleTypeMismatch(new MethodArgumentTypeMismatchException(
                                "gitlabx", HttpStatus.class, "provider", anyParameter(), null)),
                        400,
                        "COMMON.INVALID_PARAMETER",
                        "Invalid parameter: 'provider' has an unrecognised value",
                        null),
                row(
                        "a missing multipart part is a 400 naming it",
                        h -> h.handleMissingPart(new MissingServletRequestPartException("files")),
                        400,
                        "COMMON.INVALID_PARAMETER",
                        "Invalid parameter: multipart part 'files' is required",
                        null),
                row(
                        "an unsupported content type is a 415 naming it",
                        h -> h.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException(
                                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON))),
                        415,
                        "COMMON.UNSUPPORTED_MEDIA_TYPE",
                        "Unsupported media type: text/plain",
                        null),
                row(
                        "a request with no content type is a 415 saying none",
                        h -> h.handleUnsupportedMediaType(new HttpMediaTypeNotSupportedException("no content type")),
                        415,
                        "COMMON.UNSUPPORTED_MEDIA_TYPE",
                        "Unsupported media type: none",
                        null),
                row(
                        "a known path at the wrong verb is a 405 naming the verb",
                        h -> h.handleBadMethod(new HttpRequestMethodNotSupportedException("PUT")),
                        405,
                        "COMMON.NOT_FOUND",
                        "method PUT is not supported on this path",
                        null),
                row(
                        "an oversized upload reports the resolver's byte counts when it has them",
                        h -> h.handleTooLarge(new MaxUploadSizeExceededException(
                                20_971_520, new IllegalStateException("size 31457280 exceeds maximum 20971520"))),
                        413,
                        "COMMON.PAYLOAD_TOO_LARGE",
                        "Upload exceeded size limit: size 31457280 exceeds maximum 20971520",
                        null),
                row(
                        "an oversized upload with no cause names the configured limit in MB",
                        h -> h.handleTooLarge(new MaxUploadSizeExceededException(52_428_800)),
                        413,
                        "COMMON.PAYLOAD_TOO_LARGE",
                        "Upload exceeded size limit: upload exceeded the per-request limit of 50 MB; raise"
                                + " spring.servlet.multipart.max-request-size or split the upload",
                        null),
                row(
                        "an oversized upload with no known limit falls back to the exception's message",
                        h -> h.handleTooLarge(unknownLimit),
                        413,
                        "COMMON.PAYLOAD_TOO_LARGE",
                        "Upload exceeded size limit: " + unknownLimit.getMessage(),
                        null),
                row(
                        "a 5xx ResponseStatusException never leaks its reason (SQL, internal state)",
                        h -> h.handleResponseStatus(
                                new ResponseStatusException(
                                        HttpStatus.BAD_GATEWAY, "insert into provider_key failed: duplicate key"),
                                REQUEST),
                        502,
                        "COMMON.INTERNAL",
                        "Internal server error",
                        null),
                row(
                        "a status code Spring has no constant for answers 500, not a crash in the handler",
                        h -> h.handleResponseStatus(
                                new ResponseStatusException(HttpStatusCode.valueOf(599), "odd upstream"), REQUEST),
                        500,
                        "COMMON.INTERNAL",
                        "Internal server error",
                        null),
                row(
                        "a 4xx ResponseStatusException surfaces its developer-written reason",
                        h -> h.handleResponseStatus(
                                new ResponseStatusException(HttpStatus.CONFLICT, "slug taken"), REQUEST),
                        409,
                        "CONFLICT",
                        "slug taken",
                        null),
                row(
                        "a 4xx ResponseStatusException with no reason uses the status phrase",
                        h -> h.handleResponseStatus(new ResponseStatusException(HttpStatus.GONE), REQUEST),
                        410,
                        "GONE",
                        "Gone",
                        null),
                row(
                        "an unhandled exception answers the generic 500, never its own message",
                        h -> h.handleFallback(new IllegalStateException("row 7 of tenant acme is corrupt"), REQUEST),
                        500,
                        "COMMON.INTERNAL",
                        "Internal server error",
                        null));
    }

    private static Arguments row(
            String bug,
            Function<GlobalExceptionHandler, ResponseEntity<ApiResponse<Void>>> handle,
            int status,
            String code,
            String message,
            @Nullable Map<String, String> details) {
        return Arguments.of(bug, handle, status, new ErrorBody(code, message, details));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void answersTheStatusAndEnvelopeForEachException(
            String bug,
            Function<GlobalExceptionHandler, ResponseEntity<ApiResponse<Void>>> handle,
            int status,
            ErrorBody expected) {
        ResponseEntity<ApiResponse<Void>> response = handle.apply(new GlobalExceptionHandler());

        assertEquals(status, response.getStatusCode().value(), bug);
        assertEquals(ApiResponse.failure(status, expected), response.getBody(), bug);
    }
}
