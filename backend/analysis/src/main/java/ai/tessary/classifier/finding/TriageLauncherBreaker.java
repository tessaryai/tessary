// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.config.ClassifierProperties;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parks the triage drain while the launcher refuses everyone, so a bad credential costs one trip and one log line
 * instead of dead-lettering every queued finding. In memory because the condition is re-testable in one call and should
 * be per instance. No explicit half-open state: after the cooldown the drain runs and the first failures re-trip it.
 */
@Component
public class TriageLauncherBreaker {

    private static final Logger log = LoggerFactory.getLogger(TriageLauncherBreaker.class);

    private final ClassifierProperties props;

    /** Reset by any successful run, so it trips only on consecutive failures. */
    private final AtomicInteger consecutive = new AtomicInteger();

    private volatile Instant openUntil = Instant.EPOCH;

    /** For the log line that says the drain resumed. */
    private volatile @Nullable String reason;

    public TriageLauncherBreaker(ClassifierProperties props) {
        this.props = props;
    }

    /** Cheap enough to call on every tick. */
    public boolean isOpen() {
        return Instant.now().isBefore(openUntil);
    }

    public long secondsRemaining() {
        long remaining = Duration.between(Instant.now(), openUntil).toSeconds();
        return Math.max(0, remaining);
    }

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

    /** Logs only on the transition. */
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
