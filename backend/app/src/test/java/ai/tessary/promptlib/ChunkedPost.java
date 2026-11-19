// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import jakarta.servlet.ServletContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * A JSON POST that declares no length, as a chunked request does. MockMvc derives the length from the content, so the
 * request it builds has to be one that reports {@code -1}.
 */
final class ChunkedPost extends AbstractMockHttpServletRequestBuilder<ChunkedPost> {

    ChunkedPost(String uri, String json) {
        super(HttpMethod.POST);
        uri(uri);
        header("Transfer-Encoding", "chunked");
        contentType(MediaType.APPLICATION_JSON);
        content(json);
    }

    @Override
    protected MockHttpServletRequest createServletRequest(ServletContext servletContext) {
        return new MockHttpServletRequest(servletContext) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }
}
