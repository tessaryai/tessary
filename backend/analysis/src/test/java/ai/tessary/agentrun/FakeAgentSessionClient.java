// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.sandbox.AgentSpanTelemetry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/**
 * An {@link AgentSessionClient} that plays one scripted reply per message. Events get their seq as they are emitted; a
 * stream replays every event after the seq it is opened at and ends, without {@code closed}, when it reaches the last
 * one emitted. {@link #CUT} ends the stream that reaches it once, and a {@link Do} runs once when a stream reaches it.
 * An abort ends an open reply as {@code aborted}; a stop then closes the session.
 */
final class FakeAgentSessionClient implements AgentSessionClient {

    interface Item {}

    /** An event whose seq the client gives it when it is emitted. */
    interface At extends Item {
        SessionEvent at(long seq);
    }

    record Do(Runnable action) implements Item {}

    private record Cut() implements Item {}

    static final Item CUT = new Cut();

    static final AgentSpanTelemetry.AgentUsage NO_USAGE = new AgentSpanTelemetry.AgentUsage(0, 0, 0, 0, null);

    static At text(String delta) {
        return seq -> new SessionEvent.Text(seq, delta);
    }

    static At toolStart(String callId, @Nullable String server, String tool, String input) {
        return seq -> new SessionEvent.ToolStart(seq, callId, server, tool, input);
    }

    static At toolEnd(String callId, boolean ok) {
        return seq -> new SessionEvent.ToolEnd(seq, callId, "out", 3, 10, ok);
    }

    static At failure(String kind) {
        return seq -> new SessionEvent.Failure(seq, kind, "detail of " + kind);
    }

    static At end(SessionEvent.EndReason reason, long outputTokens) {
        return seq -> new SessionEvent.MessageEnd(
                seq, new AgentSpanTelemetry.AgentUsage(10, outputTokens, 0, 0, BigDecimal.ONE), reason);
    }

    static At end() {
        return end(SessionEvent.EndReason.DONE, 5);
    }

    static At closed(SessionEvent.CloseReason reason) {
        return seq -> new SessionEvent.Closed(seq, reason);
    }

    private record Entry(Item item, @Nullable SessionEvent event, AtomicBoolean done) {}

    final List<Start> starts = Collections.synchronizedList(new ArrayList<>());
    final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    /** Every call in order: {@code send}, {@code abort}, {@code stop}, {@code events <after>}, {@code close}. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private final Deque<List<Item>> replies = new ArrayDeque<>();
    private final List<Entry> emitted = Collections.synchronizedList(new ArrayList<>());
    private long seq;
    private boolean replyOpen;
    private boolean closed;

    /** Each send emits the next reply; a send with none left emits nothing. */
    FakeAgentSessionClient reply(Item... items) {
        replies.add(List.of(items));
        return this;
    }

    /** True once a stream has handed the caller {@code closed}. */
    volatile boolean closedRead;

    /** A session that emits nothing on abort or stop, so it never closes. */
    boolean silent;

    @Nullable
    RuntimeException abortFails;

    @Nullable
    RuntimeException stopFails;

    /** What every send returns once it is not {@code ACCEPTED}. */
    @Nullable
    SendOutcome refuse;

    /** The n-th {@code events} call, from 0, throws this on its first read. */
    final Map<Integer, RuntimeException> streamFailures = new HashMap<>();

    private int streams;

    @Override
    public Session start(Start start) {
        starts.add(start);
        return new Session("s-1");
    }

    @Override
    public synchronized SendOutcome send(String sessionId, String text) {
        calls.add("send");
        if (closed) return SendOutcome.CLOSED;
        if (refuse != null) return refuse;
        sent.add(text);
        List<Item> reply = replies.poll();
        if (reply == null) return SendOutcome.ACCEPTED;
        replyOpen = true;
        reply.forEach(this::emit);
        return SendOutcome.ACCEPTED;
    }

    @Override
    public synchronized void abort(String sessionId) {
        calls.add("abort");
        if (abortFails != null) throw abortFails;
        if (silent) return;
        if (replyOpen && !closed) emit(end(SessionEvent.EndReason.ABORTED, 0));
    }

    @Override
    public synchronized void stop(String sessionId) {
        calls.add("stop");
        if (stopFails != null) throw stopFails;
        if (closed || silent) return;
        if (replyOpen) emit(end(SessionEvent.EndReason.ABORTED, 0));
        emit(closed(SessionEvent.CloseReason.STOPPED));
    }

    private void emit(Item item) {
        SessionEvent event = item instanceof At at ? at.at(++seq) : null;
        if (event instanceof SessionEvent.MessageEnd) replyOpen = false;
        if (event instanceof SessionEvent.Closed) closed = true;
        emitted.add(new Entry(item, event, new AtomicBoolean()));
    }

    @Override
    public Stream<SessionEvent> events(String sessionId, long afterSeq) {
        calls.add("events " + afterSeq);
        Reader reader = new Reader(afterSeq, streamFailures.get(streams++));
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(reader, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() -> {
                    calls.add("close");
                    reader.closed = true;
                });
    }

    private final class Reader implements Iterator<SessionEvent> {
        private final long after;
        private @Nullable RuntimeException failure;
        private int i;
        private boolean ended;
        volatile boolean closed;

        Reader(long after, @Nullable RuntimeException failure) {
            this.after = after;
            this.failure = failure;
        }

        @Override
        public boolean hasNext() {
            RuntimeException f = failure;
            failure = null;
            if (f != null) throw f;
            while (!ended && !closed && i < emitted.size()) {
                Entry e = emitted.get(i);
                if (e.event() != null && e.event().seq() > after) return true;
                i++;
                if (e.item() instanceof Cut && e.done().compareAndSet(false, true)) ended = true;
                if (e.item() instanceof Do d && e.done().compareAndSet(false, true))
                    d.action().run();
            }
            return false;
        }

        @Override
        public SessionEvent next() {
            if (!hasNext()) throw new NoSuchElementException();
            SessionEvent event = emitted.get(i++).event();
            if (event instanceof SessionEvent.Closed) closedRead = true;
            return java.util.Objects.requireNonNull(event);
        }
    }

    /** Timers that fire only when the test says so, and pauses that return at once. */
    static final class FakeDeadlines implements AgentSessionRunner.Deadlines {

        record Scheduled(Duration delay, Runnable action, AtomicBoolean cancelled) {}

        final List<Scheduled> scheduled = Collections.synchronizedList(new ArrayList<>());
        final List<Duration> pauses = Collections.synchronizedList(new ArrayList<>());

        /** Timers set for one of these delays fire as they are set. */
        final Set<Duration> immediate = new HashSet<>();

        boolean interruptPauses;

        @Override
        public Timer after(Duration delay, Runnable action) {
            Scheduled s = new Scheduled(delay, action, new AtomicBoolean());
            scheduled.add(s);
            if (immediate.contains(delay) && s.cancelled().compareAndSet(false, true)) action.run();
            return () -> s.cancelled().set(true);
        }

        @Override
        public void pause(Duration delay) throws InterruptedException {
            pauses.add(delay);
            if (interruptPauses) throw new InterruptedException();
        }

        /** Runs every live timer set for {@code delay}. */
        void fire(Duration delay) {
            for (Scheduled s : List.copyOf(scheduled)) {
                if (s.delay().equals(delay) && s.cancelled().compareAndSet(false, true))
                    s.action().run();
            }
        }
    }
}
