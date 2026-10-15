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
 * A size-capped {@code BodyHandler<String>} for credentialed outbound calls: on overflow it cancels the upstream and
 * fails the body, so a hostile response never buffers past the cap. A drop-in for {@code BodyHandlers.ofString()}.
 */
public final class BoundedBody {

    /** Far above any real payload, low enough that a runaway upstream can't OOM the box. */
    public static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

    private BoundedBody() {}

    public static HttpResponse.BodyHandler<String> string() {
        return info -> new BoundedStringSubscriber(MAX_RESPONSE_BYTES);
    }

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
                    // Flow guarantees onSubscribe precedes onNext.
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
