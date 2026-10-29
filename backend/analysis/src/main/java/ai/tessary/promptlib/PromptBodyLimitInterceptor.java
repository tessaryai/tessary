// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses a prompt request whose declared body is over 1 MiB before any of it is read. A chunked body declares no
 * length ({@code -1}) and passes here; {@link PromptBodyLimitAdvice} bounds it as it is read.
 */
@Component
public class PromptBodyLimitInterceptor implements HandlerInterceptor {

    static final long MAX_BYTES = 1_048_576;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getContentLengthLong() > MAX_BYTES) {
            throw new TessaryException(CommonError.INVALID_BODY, "request body exceeds 1048576 bytes");
        }
        return true;
    }
}
