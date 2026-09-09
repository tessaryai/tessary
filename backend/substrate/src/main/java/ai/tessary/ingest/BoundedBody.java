// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * A size-capped {@code BodyHandler<String>} for credentialed outbound calls. It counts bytes as
 * they arrive and, on overflow, cancels the upstream and fails the body future — so a hostile or
 * broken response never buffers more than the cap into the heap. Shared by every outbound client
 * (the ingest {@link HttpJson}, the Git provider clients), the same way {@link UrlGuard}
 * is the shared SSRF guard for those calls.
 *
 * <p>The handler stays typed {@code <String>}, so it is a drop-in for
 * {@code HttpResponse.BodyHandlers.ofString()} and leaves callers (and the tests that swap the
 * {@code HttpClient}) unchanged.
 */
public final class BoundedBody {

    /** Ceiling for a JSON response body — far above any real trace page / API payload, low enough
     *  that a runaway upstream can't OOM the box. */
    public static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

    private BoundedBody() {}

    /** A string body handler capped at {@link #MAX_RESPONSE_BYTES}. */
    public static HttpResponse.BodyHandler<String> string() {
        return string(MAX_RESPONSE_BYTES);
    }

    /** A string body handler capped at {@code maxBytes}. */
    public static HttpResponse.BodyHandler<String> string(long maxBytes) {
        return info -> new BoundedStringSubscriber(maxBytes);
    }

    /** Delegates to the JDK UTF-8 string subscriber, counting bytes and aborting past the cap. */
    private static final class BoundedStringSubscriber implements HttpResponse.BodySubscriber<String> {
        private final long maxBytes;
        private final HttpResponse.BodySubscriber<String> delegate =
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private final AtomicLong seen = new AtomicLong();
        private final AtomicBoolean done = new AtomicBoolean();
        private Flow.@Nullable Subscription subscription;

        BoundedStringSubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            delegate.onSubscribe(s);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done.get()) return;
            long total = seen.addAndGet(
                    buffers.stream().mapToLong(ByteBuffer::remaining).sum());
            if (total > maxBytes) {
                if (done.compareAndSet(false, true)) {
                    // onSubscribe always precedes onNext per the Flow contract, so subscription is set.
                    Flow.Subscription s = subscription;
                    if (s != null) s.cancel();
                    delegate.onError(new IOException("response exceeded " + maxBytes + " bytes"));
                }
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable t) {
            if (done.compareAndSet(false, true)) delegate.onError(t);
        }

        @Override
        public void onComplete() {
            if (done.compareAndSet(false, true)) delegate.onComplete();
        }
    }
}
