// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.worker;

import ai.tessary.evals.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.evals.classifier.ClassifierRow;
import ai.tessary.evals.classifier.finding.FindingEvidenceRepository;
import ai.tessary.evals.classifier.finding.FindingRepository;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import ai.tessary.evals.tenant.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-span classifiers' own arming: N detections in window W open or refresh a FINDING, with the
 * spans that fired as its evidence.
 *
 * <p>This is where the threshold {@code alert_rule} rows went. That rule was the alerting subsystem's
 * door into classifier detections — it counted a window and paged someone, and a second consumer
 * (the deleted {@code ClassifierSignalSource}) read the same decision to open a case. Neither door
 * exists now. The number a human chose survives as {@code config_json.arming} (migration {@code 0089}
 * translated every enabled rule onto its classifier), and the classifier acts on it directly, inside
 * its own sweep, one transaction with the detection writes.
 *
 * <p><b>Why a finding rather than an alert.</b> A finding is the thing the rest of the platform can
 * act on: a case opens from it, evidence hangs off it, a human rules on it once and the ruling sticks.
 * A threshold breach that only sent a message had none of that, which is why the same window firing on
 * every heartbeat was a notification problem rather than a state a human could close.
 *
 * <p><b>A classifier with no arming config never files anything.</b> That is the deliberate empty
 * state, unchanged from the alert rules it replaces: the platform ships zero armed classifiers, and
 * inventing a default bar for someone else's classifier would open cases about a line they never drew.
 */
@Component
public class ClassifierArming {

    private static final Logger log = LoggerFactory.getLogger(ClassifierArming.class);

    /** The config member 0089 writes and this reads. */
    private static final String ARMING = "arming";

    private static final int DEFAULT_WINDOW_SECONDS = 86_400;

    /**
     * How many empty windows count as a recovery. A classifier that breaches its bar in consecutive
     * windows is one spell and keeps one finding; two whole windows with no refresh is the classifier
     * having stopped, so the next breach starts a new spell and moves the onset — which is what lets a
     * case a human resolved reopen when the behaviour comes back, rather than on the next tick.
     */
    private static final int QUIET_WINDOWS = 2;

    private final ClassifierDetectionWriteRepository detections;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ObjectMapper mapper;

    public ClassifierArming(
            ClassifierDetectionWriteRepository detections,
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ObjectMapper mapper) {
        this.detections = detections;
        this.findings = findings;
        this.evidence = evidence;
        this.mapper = mapper;
    }

    /** One classifier's arming configuration: how many of what, over how long. */
    record Config(String basis, long threshold, long windowSeconds) {}

    /**
     * Evaluate the {@code classifier}'s bar over the window {@code now} falls in, and file a finding when it is
     * crossed.
     *
     * <p>The window is QUANTIZED — {@code floor(now / W) * W} — for the reason the alert evaluator
     * quantized it: a free-floating {@code [now - W, now)} would make the count, and therefore the
     * finding's payload, different on every heartbeat inside one window. The bucket is the unit the
     * owner configured, and it is what the finding reports.
     *
     * @param firedRefs the subjects this sweep flagged, in sweep order — the finding's evidence
     * @return the finding id when one was opened or refreshed, else null
     */
    @Transactional
    public @Nullable String evaluate(
            ClassifierRow signal, String projectId, List<FindingEvidenceRepository.Ref> firedRefs, Instant now) {
        Config config = configOf(signal);
        if (config == null) return null;

        long win = config.windowSeconds();
        Instant windowStart = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), win) * win);
        Instant windowEnd = windowStart.plus(Duration.ofSeconds(win));
        boolean trackingOnly = ClassifierRow.Mode.TRACKING.equals(signal.mode());

        // distinct_users is a SESSION count — the user proxy the threshold rules used, carried over
        // verbatim so a translated rule keeps counting what its owner set it to count. every_match and
        // event_count both count detections; they differed only in the threshold, and 0089 folded
        // every_match's forced threshold of one into the translated config.
        boolean bySession = "distinct_users".equals(config.basis());
        long observed = bySession
                ? detections.countDistinctSessionsInWindow(
                        signal.detector(),
                        projectId,
                        signal.id(),
                        windowStart.toString(),
                        windowEnd.toString(),
                        trackingOnly)
                : detections.countInWindow(
                        signal.detector(),
                        projectId,
                        signal.id(),
                        windowStart.toString(),
                        windowEnd.toString(),
                        trackingOnly);
        if (observed < config.threshold()) return null;

        String at = now.toString();
        String quietBefore = now.minus(Duration.ofSeconds(win * QUIET_WINDOWS)).toString();
        FindingRepository.Recorded recorded = findings.recordArmedWindow(
                Ids.ulid(),
                projectId,
                signal.classifierKey(),
                signal.id(),
                signal.name(),
                observed,
                /* callSiteId */ null, // a classifier is project-wide; it implicates no single call site
                payload(signal, config, observed, windowStart, windowEnd),
                quietBefore,
                at);

        // Same transaction as the finding write, so a finding never exists without the evidence that
        // justified it. The writer caps and de-duplicates, so calling it on every armed sweep adds
        // witnesses while the finding is open and stops at the cap rather than growing the pin.
        int stored = evidence.record(projectId, recorded.findingId(), "member", firedRefs, at);

        StructuredLog.info(log, Markers.OPS, "signal.armed")
                .message(
                        "%s crossed its bar — %d detection(s) in %ds",
                        signal.classifierKey(), observed, config.windowSeconds())
                .field("project", projectId)
                .field("signal", signal.classifierKey())
                .field("classifierId", signal.id())
                .field("finding", recorded.findingId())
                .field("opened", recorded.created())
                .field("observed", observed)
                .field("threshold", config.threshold())
                .field("windowSeconds", config.windowSeconds())
                .field("evidenceStored", stored)
                .log();
        return recorded.findingId();
    }

    /**
     * Read the {@code arming} block out of a classifier's config, or null when it is absent or
     * unreadable. Unreadable degrades to unarmed rather than throwing: a malformed config must not stop
     * the classifier from detecting, which is the part that cannot be recomputed later.
     */
    @Nullable
    Config configOf(ClassifierRow signal) {
        if (!this.detections.writesDetections(signal.detector())) return null;
        String json = signal.configJson();
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = mapper.readTree(json).path(ARMING);
            if (node.isMissingNode() || !node.isObject()) return null;
            String basis = node.path("basis").asText("event_count");
            long threshold = Math.max(1, node.path("threshold").asLong(1));
            long window = Math.max(1, node.path("window_seconds").asLong(DEFAULT_WINDOW_SECONDS));
            return new Config(basis, threshold, window);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * What the finding says about itself: the bar, the window it was measured over, and the count. The
     * basis rides along because "17 detections" and "17 sessions" are different claims and a case title
     * built from this has to be able to say which.
     */
    private String payload(ClassifierRow signal, Config config, long observed, Instant start, Instant end) {
        try {
            return mapper.writeValueAsString(java.util.Map.of(
                    "cause_kind", "armed_window",
                    "native_cause_key", signal.classifierKey(),
                    "basis", config.basis(),
                    "observed", observed,
                    "threshold", config.threshold(),
                    "window_seconds", config.windowSeconds(),
                    "window_start", start.toString(),
                    "window_end", end.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"cause_kind\":\"armed_window\"}";
        }
    }
}
