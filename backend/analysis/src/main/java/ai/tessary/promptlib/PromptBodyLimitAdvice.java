// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Bounds the bytes a prompt request body may hold while it is read, so a chunked body, which declares no length and
 * passes {@link PromptBodyLimitInterceptor}, never reaches Jackson whole.
 */
@ControllerAdvice(assignableTypes = {PromptController.class, PromptPublicController.class, PlaygroundController.class})
public class PromptBodyLimitAdvice extends RequestBodyAdviceAdapter {

    @Override
    public boolean supports(
            MethodParameter methodParameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage inputMessage,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = inputMessage.getBody().readNBytes((int) PromptBodyLimitInterceptor.MAX_BYTES + 1);
        if (body.length > PromptBodyLimitInterceptor.MAX_BYTES) {
            throw new TessaryException(CommonError.INVALID_BODY, "request body exceeds 1048576 bytes");
        }
        HttpHeaders headers = inputMessage.getHeaders();
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
