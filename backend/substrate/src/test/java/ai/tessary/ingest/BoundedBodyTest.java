// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The size-capped body reader every credentialed outbound client uses. Driven through the JDK's own
 * subscriber protocol with a hand-written subscription, so no network is involved.
 */
class BoundedBodyTest {

    private static final HttpResponse.ResponseInfo OK = new HttpResponse.ResponseInfo() {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (k, v) -> true);
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    };

    /** Records whether the reader hung up on the upstream. */
    private static final class Upstream implements Flow.Subscription {
        boolean cancelled;

        @Override
        public void request(long n) {}

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    @Test
    void aBodyUnderTheCapArrivesWhole() throws Exception {
        var body = BoundedBody.string().apply(OK);
        var upstream = new Upstream();
        body.onSubscribe(upstream);

        body.onNext(List.of(utf8("{\"name\":"), utf8("\"caf")));
        body.onNext(List.of(utf8("é\"}")));
        body.onComplete();

        assertEquals("{\"name\":\"café\"}", body.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertFalse(upstream.cancelled);
    }

    @Test
    void aBodyPastTheCapHangsUpOnTheUpstreamAndFailsRatherThanBuffering() {
        var body = BoundedBody.string().apply(OK);
        var upstream = new Upstream();
        body.onSubscribe(upstream);
        body.onNext(List.of(utf8("ok")));

        // Views over one mebibyte: their remaining() counts toward the cap without allocating the whole of it.
        ByteBuffer mib = ByteBuffer.allocate(1 << 20);
        int views = (int) (BoundedBody.MAX_RESPONSE_BYTES >> 20);
        body.onNext(Stream.generate(mib::duplicate).limit(views).toList());

        assertTrue(upstream.cancelled, "the upstream is cancelled the moment the cap is crossed");
        var failure = assertThrows(
                ExecutionException.class,
                () -> body.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());

        // Anything the upstream still delivers is dropped; the body stays failed.
        body.onNext(List.of(utf8("late")));
        body.onComplete();
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void anUpstreamErrorFailsTheBodyAndALaterCompletionCannotHideIt() {
        var body = BoundedBody.string().apply(OK);
        body.onSubscribe(new Upstream());
        IOException reset = new IOException("connection reset");

        body.onError(reset);
        body.onComplete();
        body.onError(new IOException("second"));

        var failure = assertThrows(
                ExecutionException.class,
                () -> body.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertSame(reset, failure.getCause());
    }

    private static ByteBuffer utf8(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }
}
