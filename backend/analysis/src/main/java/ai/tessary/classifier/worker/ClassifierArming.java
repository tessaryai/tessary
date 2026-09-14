// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.FacetWindow;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.FiredFacet;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.SpanKey;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>A classifier with no arming config never files anything.</b> A user-authored classifier ships
 * unarmed: inventing a bar for someone else's classifier would open cases about a line they never drew.
 * A built-in may ship armed, because the platform authored both the detector and the bar.
 *
 * <p><b>Two shapes.</b> By default a classifier holds one finding, keyed on the classifier, counted over
 * the window {@code now} falls in. A classifier in {@link #FACET_KEYS} instead holds one finding per call
 * site and per facet of what it detected, counted over the window each span actually happened in; see
 * {@link #evaluateFaceted}.
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

    /**
     * The classifiers whose arming is faceted, each mapped to the evidence member its facet is read from.
     *
     * <p>A leaked credential is a statement about one place and one kind of secret. Filed against the whole
     * classifier, an AWS key leaking from one call site and a GitHub token from another would share one
     * finding, and a single ruling would silence both, including the one nobody looked at.
     */
    private static final Map<String, String> FACET_KEYS = Map.of(BuiltInDetector.Kind.SECRET_LEAK, "pattern");

    /**
     * Witnesses kept per faceted finding. A cause that keeps firing needs a handful of instances a reader
     * can open, not every span it ever fired on pinned against retention.
     */
    static final int MAX_WITNESSES = 50;

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

    /**
     * One classifier's arming configuration: how many of what, over how long, at which band.
     *
     * @param confidence {@code high} to count only the HIGH band, {@code any} for both, or null to follow
     *     the classifier's mode. Set explicitly by a built-in whose low band should stay visible on the
     *     page (which is what discovery mode means) without counting toward its bar.
     */
    record Config(
            String basis,
            long threshold,
            long windowSeconds,
            @Nullable String confidence) {

        boolean highOnly(ClassifierRow signal) {
            return switch (confidence == null ? "" : confidence) {
                case "high" -> true;
                case "any" -> false;
                default -> ClassifierRow.Mode.TRACKING.equals(signal.mode());
            };
        }

        boolean bySession() {
            // distinct_users is a SESSION count, the user proxy the threshold rules used, carried over
            // verbatim so a translated rule keeps counting what its owner set it to count. every_match and
            // event_count both count detections; they differed only in the threshold, and 0089 folded
            // every_match's forced threshold of one into the translated config.
            return "distinct_users".equals(basis);
        }
    }

    /**
     * Evaluate the {@code signal}'s bar against this sweep's firings and file a finding for every scope
     * that crossed it.
     *
     * @param firedRefs the subjects this sweep flagged, in sweep order — the finding's evidence
     * @return the ids of the findings opened or refreshed, oldest window first; empty when nothing crossed
     */
    @Transactional
    public List<String> evaluate(
            ClassifierRow signal, String projectId, List<FindingEvidenceRepository.Ref> firedRefs, Instant now) {
        Config config = configOf(signal);
        if (config == null) return List.of();
        String facetKey = FACET_KEYS.get(signal.detector());
        if (facetKey != null) return evaluateFaceted(signal, projectId, firedRefs, config, facetKey, now);
        String findingId = evaluateWhole(signal, projectId, firedRefs, config, now);
        return findingId == null ? List.of() : List.of(findingId);
    }

    /**
     * The classifier-wide shape: one finding, counted over the window {@code now} falls in.
     *
     * <p>The window is QUANTIZED — {@code floor(now / W) * W} — for the reason the alert evaluator
     * quantized it: a free-floating {@code [now - W, now)} would make the count, and therefore the
     * finding's payload, different on every heartbeat inside one window. The bucket is the unit the
     * owner configured, and it is what the finding reports.
     */
    private @Nullable String evaluateWhole(
            ClassifierRow signal,
            String projectId,
            List<FindingEvidenceRepository.Ref> firedRefs,
            Config config,
            Instant now) {
        long win = config.windowSeconds();
        Instant windowStart = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), win) * win);
        Instant windowEnd = windowStart.plus(Duration.ofSeconds(win));
        boolean highOnly = config.highOnly(signal);

        long observed = config.bySession()
                ? detections.countDistinctSessionsInWindow(
                        signal.detector(),
                        projectId,
                        signal.id(),
                        windowStart.toString(),
                        windowEnd.toString(),
                        highOnly)
                : detections.countInWindow(
                        signal.detector(),
                        projectId,
                        signal.id(),
                        windowStart.toString(),
                        windowEnd.toString(),
                        highOnly);
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
                payload(signal.classifierKey(), config, observed, windowStart, windowEnd, null, null, null),
                quietBefore,
                at);

        // Same transaction as the finding write, so a finding never exists without the evidence that
        // justified it. Calling it on every armed sweep adds new members while the finding is open.
        int stored = evidence.record(projectId, recorded.findingId(), FindingEvidenceRow.Role.MEMBER, firedRefs, at);

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
     * The faceted shape: one finding per call site and facet, counted over event-time windows.
     *
     * <p><b>Every window this sweep touched is evaluated, not only the current one.</b> Windows are
     * bucketed on when the span happened, and a backfill or a late upload hands the sweep detections from
     * weeks ago. Evaluating only the window {@code now} falls in would never file those, and at a bar of
     * one that means a leak discovered late is never a finding at all.
     *
     * <p>Windows are filed oldest first, so a facet that fired across several walks forward through them,
     * and {@link FindingRepository#recordArmedFacet} keeps a window that arrives out of order from moving
     * the finding backwards. Evidence is {@link FindingEvidenceRow.Role#WITNESS}, capped at
     * {@link #MAX_WITNESSES}: instances to open, not an enumeration of the population.
     */
    private List<String> evaluateFaceted(
            ClassifierRow signal,
            String projectId,
            List<FindingEvidenceRepository.Ref> firedRefs,
            Config config,
            String facetKey,
            Instant now) {
        List<SpanKey> spans = new ArrayList<>(firedRefs.size());
        for (FindingEvidenceRepository.Ref ref : firedRefs) {
            if (ref.traceId() != null && ref.spanId() != null) spans.add(new SpanKey(ref.traceId(), ref.spanId()));
        }
        if (spans.isEmpty()) return List.of();

        long win = config.windowSeconds();
        boolean highOnly = config.highOnly(signal);
        List<FiredFacet> fired =
                detections.firedFacets(signal.detector(), projectId, signal.id(), spans, facetKey, win, highOnly);
        if (fired.isEmpty()) return List.of();

        Map<FacetScope, List<FindingEvidenceRepository.Ref>> witnesses = new LinkedHashMap<>();
        for (FiredFacet f : fired) {
            witnesses
                    .computeIfAbsent(
                            new FacetScope(f.callSiteId(), f.facet(), f.windowStartEpochSecond()),
                            k -> new ArrayList<>())
                    .add(FindingEvidenceRepository.Ref.span(f.traceId(), f.spanId()));
        }
        List<FacetWindow> windows = detections.countFacetWindows(
                signal.detector(), projectId, signal.id(), facetKey, win, highOnly, config.bySession(), fired);

        String at = now.toString();
        Set<String> filed = new LinkedHashSet<>();
        int opened = 0;
        for (FacetWindow w : windows) {
            if (w.observed() < config.threshold()) continue;
            Instant windowStart = Instant.ofEpochSecond(w.windowStartEpochSecond());
            Instant windowEnd = windowStart.plusSeconds(win);
            FindingRepository.Recorded recorded = findings.recordArmedFacet(
                    Ids.ulid(),
                    projectId,
                    signal.classifierKey(),
                    signal.id(),
                    signal.name(),
                    w.callSiteId(),
                    w.facet(),
                    w.observed(),
                    windowStart.toString(),
                    w.lastSeenAt().toString(),
                    payload(
                            signal.classifierKey(),
                            config,
                            w.observed(),
                            windowStart,
                            windowEnd,
                            w.callSiteId(),
                            w.facet(),
                            w.anyHigh() ? FindingRow.Confidence.HIGH : FindingRow.Confidence.LOW),
                    windowStart.minusSeconds(win * QUIET_WINDOWS).toString(),
                    at);
            List<FindingEvidenceRepository.Ref> refs = witnesses.getOrDefault(
                    new FacetScope(w.callSiteId(), w.facet(), w.windowStartEpochSecond()), List.of());
            int stored = evidence.recordUpTo(
                    projectId, recorded.findingId(), FindingEvidenceRow.Role.WITNESS, refs, MAX_WITNESSES, at);
            if (recorded.created()) opened++;
            filed.add(recorded.findingId());

            StructuredLog.info(log, Markers.OPS, "signal.armed")
                    .message(
                            "%s crossed its bar for %s at %s — %d detection(s) in the %ds window from %s",
                            signal.classifierKey(),
                            w.facet(),
                            w.callSiteId() == null ? "no call site" : w.callSiteId(),
                            w.observed(),
                            win,
                            windowStart)
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("finding", recorded.findingId())
                    .field("opened", recorded.created())
                    .field("callSiteId", w.callSiteId())
                    .field("facet", w.facet())
                    .field("windowStart", windowStart.toString())
                    .field("observed", w.observed())
                    .field("threshold", config.threshold())
                    .field("windowSeconds", win)
                    .field("witnessesStored", stored)
                    .log();
        }

        if (!windows.isEmpty()) {
            StructuredLog.info(log, Markers.OPS, "signal.armed.facets")
                    .message(
                            "%s evaluated %d facet window(s) from %d fired span(s), filed %d finding(s), opened %d",
                            signal.classifierKey(), windows.size(), fired.size(), filed.size(), opened)
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("firedSpans", fired.size())
                    .field("windows", windows.size())
                    .field("filed", filed.size())
                    .field("opened", opened)
                    .log();
        }
        return List.copyOf(filed);
    }

    /** Which finding and which window a fired span's witness belongs to. */
    private record FacetScope(@Nullable String callSiteId, String facet, long windowStartEpochSecond) {}

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
            JsonNode band = node.path("confidence");
            return new Config(basis, threshold, window, band.isTextual() ? band.asText() : null);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * What the finding says about itself: the bar, the window it was measured over, and the count. The
     * basis rides along because "17 detections" and "17 sessions" are different claims and a case title
     * built from this has to be able to say which. A faceted finding also names its call site and facet,
     * the two things its title is built from.
     */
    private String payload(
            String classifierKey,
            Config config,
            long observed,
            Instant start,
            Instant end,
            @Nullable String callSiteId,
            @Nullable String facet,
            @Nullable String confidence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cause_kind", FindingRow.Cause.ARMED_WINDOW);
        body.put("native_cause_key", facet == null ? classifierKey : facet);
        body.put("basis", config.basis());
        body.put("observed", observed);
        body.put("threshold", config.threshold());
        body.put("window_seconds", config.windowSeconds());
        body.put("window_start", start.toString());
        body.put("window_end", end.toString());
        if (facet != null) {
            body.put("facet", facet);
            if (callSiteId != null) body.put("call_site_id", callSiteId);
        }
        if (confidence != null) body.put(FindingRow.Confidence.PAYLOAD_KEY, confidence);
        try {
            return mapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";
        }
    }
}
