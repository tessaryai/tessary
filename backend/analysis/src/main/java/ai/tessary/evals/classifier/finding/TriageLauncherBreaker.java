// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import ai.tessary.evals.config.ClassifierProperties;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops the triage drain while the launcher is refusing everyone.
 *
 * <h2>What it is protecting against</h2>
 *
 * <p>A launcher answering 401 answers 401 to every finding. Without this, twenty-five queued jobs
 * each burned five attempts at full speed — {@code markRetryable} expires the lease immediately, so
 * the next tick re-claims at once — producing ~125 launch attempts, ~125 ERROR lines, and
 * twenty-five dead-lettered findings, none of which had anything wrong with them. The breaker turns
 * that into one trip, one log line, and a queue that is still intact when the credential is fixed.
 *
 * <h2>Why in memory, unlike {@code GradingBreaker}</h2>
 *
 * <p>The grading breaker is DB-persisted because it latches on CUMULATIVE spend: the fact it is
 * protecting survives a restart, and a fresh process must not start spending again from zero. This
 * one latches on a condition that is re-testable in a single call — the next probe after the
 * cooldown either succeeds or does not. Persisting it would add a table and a migration to
 * re-derive something a restart is entitled to re-learn, and a deployment-wide latch would let one
 * instance's bad network stop another's healthy drain.
 *
 * <h2>Half-open by cooldown, not by count</h2>
 *
 * <p>There is no explicit half-open state: when the cooldown expires the drain simply runs again,
 * and the first {@code failures} launcher-level failures re-trip it. A misconfigured secret
 * therefore costs a handful of probes per cooldown rather than a stream, and a launcher that came
 * back is used immediately on the next tick.
 */
@Component
public class TriageLauncherBreaker {

    private static final Logger log = LoggerFactory.getLogger(TriageLauncherBreaker.class);

    private final ClassifierProperties props;

    /**
     * Consecutive launcher-level failures. Reset by any successful run, so an intermittent launcher
     * has to fail {@code failures} times in a row — not {@code failures} times ever — to trip.
     */
    private final AtomicInteger consecutive = new AtomicInteger();

    private volatile Instant openUntil = Instant.EPOCH;

    /** The reason the current trip is open, for the log line that says the drain resumed. */
    private volatile @Nullable String reason;

    public TriageLauncherBreaker(ClassifierProperties props) {
        this.props = props;
    }

    /** Whether the drain should stay parked. Cheap enough to call on every tick. */
    public boolean isOpen() {
        return Instant.now().isBefore(openUntil);
    }

    /** Seconds until the drain may run again; zero when it already may. */
    public long secondsRemaining() {
        long remaining = Duration.between(Instant.now(), openUntil).toSeconds();
        return Math.max(0, remaining);
    }

    /**
     * A run reached the launcher and got an answer. Closes the breaker, because the condition it was
     * latched on is demonstrably over.
     */
    public void recordReachable() {
        if (consecutive.getAndSet(0) > 0 || isOpen()) {
            openUntil = Instant.EPOCH;
            String was = reason;
            reason = null;
            StructuredLog.info(log, Markers.OPS, "triage.breaker.closed")
                    .message("the triage launcher answered again; draining resumes")
                    .field("was", was == null ? "" : was)
                    .log();
        }
    }

    /**
     * The launcher itself failed. Trips once the run of failures reaches the threshold, and logs only
     * on the transition — the point of this class is that an outage costs one line, not one per job.
     */
    public void recordLauncherFailure(String why) {
        int runLength = consecutive.incrementAndGet();
        if (runLength < props.getTriageBreakerFailures() || isOpen()) return;
        long cooldown = props.getTriageBreakerCooldownSeconds();
        openUntil = Instant.now().plusSeconds(cooldown);
        reason = why;
        StructuredLog.error(log, Markers.OPS, "triage.breaker.opened")
                .message("the triage launcher failed " + runLength + " times running; parking the drain for " + cooldown
                        + "s")
                .field("failures", runLength)
                .field("cooldown_seconds", cooldown)
                .field("reason", why)
                .log();
    }
}
