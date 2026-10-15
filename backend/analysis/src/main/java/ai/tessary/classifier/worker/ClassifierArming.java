// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.CountedWindow;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.FacetWindow;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.FiredFacet;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.FiredWindow;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * N detections in window W open or refresh a finding, in the same transaction as the detection writes. A classifier
 * with no arming config never files: inventing a bar for a user-authored classifier would open cases about a line its
 * owner never drew. Every event-time window a sweep touched is counted, not only the current one, or a backfill would
 * never file.
 */
@Component
public class ClassifierArming {

    private static final Logger log = LoggerFactory.getLogger(ClassifierArming.class);

    private static final String ARMING = "arming";

    private static final int DEFAULT_WINDOW_SECONDS = 86_400;

    /**
     * Two whole windows with no refresh end a spell, so the next breach moves the onset and can reopen a case a human
     * resolved.
     */
    private static final int QUIET_WINDOWS = 2;

    /**
     * Filed against the whole classifier, leaks of different secrets at different call sites would share one finding,
     * and one ruling would silence both.
     */
    private static final Map<String, String> FACET_KEYS = Map.of(BuiltInDetector.Kind.SECRET_LEAK, "pattern");

    public static @Nullable String facetKey(String detector) {
        return FACET_KEYS.get(detector);
    }

    /** A cause that keeps firing needs a handful of instances to open, not every span pinned against retention. */
    static final int MAX_WITNESSES = 50;

    public static final String HIGH_CONFIDENCE_LEAK_SUMMARY =
            "A high-confidence credential pattern matched in the agent's output, so this leak was ruled real without triage.";

    private final ClassifierDetectionWriteRepository detections;
    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final ObjectMapper mapper;
    private final CaseOpener caseOpener;

    public ClassifierArming(
            ClassifierDetectionWriteRepository detections,
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            ObjectMapper mapper,
            CaseOpener caseOpener) {
        this.detections = detections;
        this.findings = findings;
        this.evidence = evidence;
        this.mapper = mapper;
        this.caseOpener = caseOpener;
    }

    /** @param confidence {@code high}, {@code any}, or null to follow the classifier's mode */
    public record Config(
            String basis,
            long threshold,
            long windowSeconds,
            @Nullable String confidence) {

        public boolean highOnly(ClassifierRow signal) {
            return switch (confidence == null ? "" : confidence) {
                case "high" -> true;
                case "any" -> false;
                default -> ClassifierRow.Mode.TRACKING.equals(signal.mode());
            };
        }

        public boolean bySession() {
            // distinct_users is a session count, carried over verbatim from the threshold rules so a translated rule
            // keeps counting what its owner set.
            return "distinct_users".equals(basis);
        }
    }

    /** @return the ids of the findings opened or refreshed, oldest window first */
    @Transactional
    public List<String> evaluate(
            ClassifierRow signal, String projectId, List<FindingEvidenceRepository.Ref> firedRefs, Instant now) {
        Config config = configOf(signal);
        if (config == null) return List.of();
        String facetKey = FACET_KEYS.get(signal.detector());
        if (facetKey != null) return evaluateFaceted(signal, projectId, firedRefs, config, facetKey, now);
        return evaluateWhole(signal, projectId, firedRefs, config, now);
    }

    /**
     * Every window this sweep touched is evaluated, oldest first: a backfill or late upload hands the sweep weeks-old
     * detections. {@link FindingRepository#recordArmedWindow} keeps an out-of-order window from moving the finding
     * backwards.
     */
    private List<String> evaluateWhole(
            ClassifierRow signal,
            String projectId,
            List<FindingEvidenceRepository.Ref> firedRefs,
            Config config,
            Instant now) {
        List<SpanKey> spans = new ArrayList<>(firedRefs.size());
        for (FindingEvidenceRepository.Ref ref : firedRefs) {
            if (ref.traceId() != null && ref.spanId() != null) spans.add(new SpanKey(ref.traceId(), ref.spanId()));
        }
        if (spans.isEmpty()) return List.of();

        long win = config.windowSeconds();
        boolean highOnly = config.highOnly(signal);
        List<FiredWindow> fired =
                detections.firedWindows(signal.detector(), projectId, signal.id(), spans, win, highOnly);
        if (fired.isEmpty()) return List.of();

        Map<Long, List<FindingEvidenceRepository.Ref>> members = new LinkedHashMap<>();
        for (FiredWindow f : fired) {
            members.computeIfAbsent(f.windowStartEpochSecond(), k -> new ArrayList<>())
                    .add(FindingEvidenceRepository.Ref.span(f.traceId(), f.spanId()));
        }
        List<CountedWindow> windows = detections.countWindows(
                signal.detector(), projectId, signal.id(), win, highOnly, config.bySession(), fired);

        String at = now.toString();
        List<String> filed = new ArrayList<>();
        for (CountedWindow w : windows) {
            if (w.observed() < config.threshold()) continue;
            Instant windowStart = Instant.ofEpochSecond(w.windowStartEpochSecond());
            Instant windowEnd = windowStart.plusSeconds(win);
            FindingRepository.Recorded recorded = findings.recordArmedWindow(
                    Ids.ulid(),
                    projectId,
                    signal.classifierKey(),
                    signal.id(),
                    signal.name(),
                    w.observed(),
                    /* callSiteId */ null, // a classifier is project-wide; it implicates no single call site
                    payload(signal.classifierKey(), config, w.observed(), windowStart, windowEnd, null, null, null),
                    windowStart.toString(),
                    w.lastSeenAt().toString(),
                    windowStart.minusSeconds(win * QUIET_WINDOWS).toString(),
                    at);
            if (recorded == null) continue; // a ruled finding already covers this window or a newer one

            // Same transaction as the finding write, so a finding never exists without the evidence that justified it.
            // Per-ref idempotency means a re-swept window never re-adds what it recorded.
            List<FindingEvidenceRepository.Ref> refs = members.getOrDefault(w.windowStartEpochSecond(), List.of());
            int stored = evidence.record(projectId, recorded.findingId(), FindingEvidenceRow.Role.MEMBER, refs, at);
            filed.add(recorded.findingId());

            StructuredLog.info(log, Markers.OPS, "signal.armed")
                    .message(
                            "%s crossed its bar at %s — %d detection(s) in the %ds window",
                            signal.classifierKey(), windowStart, w.observed(), win)
                    .field("project", projectId)
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("finding", recorded.findingId())
                    .field("opened", recorded.created())
                    .field("windowStart", windowStart.toString())
                    .field("observed", w.observed())
                    .field("threshold", config.threshold())
                    .field("windowSeconds", win)
                    .field("evidenceStored", stored)
                    .log();
        }
        return filed;
    }

    /**
     * Every window this sweep touched is evaluated, oldest first; at a bar of one, evaluating only the current window
     * would mean a late-discovered leak is never a finding. A high-confidence facet is ruled positive here without
     * triage, since a provider-stamped key format is a leak whoever looks. The ruling takes the row out of
     * {@code ux_finding_live}, so a re-sweep gets null from {@link FindingRepository#recordArmedFacet} rather than
     * forking a duplicate.
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
            if (recorded == null) continue; // a ruled finding already covers this window or a newer one
            List<FindingEvidenceRepository.Ref> refs = witnesses.getOrDefault(
                    new FacetScope(w.callSiteId(), w.facet(), w.windowStartEpochSecond()), List.of());
            int stored = evidence.recordUpTo(
                    projectId, recorded.findingId(), FindingEvidenceRow.Role.WITNESS, refs, MAX_WITNESSES, at);
            // Re-read rather than taken from this window: confidence is sticky across the row's windows.
            boolean high = findings.findById(projectId, recorded.findingId())
                    .map(FindingRow::highConfidence)
                    .orElse(false);
            if (high) {
                findings.recordTriage(
                        projectId,
                        recorded.findingId(),
                        FindingRow.TriageVerdict.POSITIVE,
                        HIGH_CONFIDENCE_LEAK_SUMMARY,
                        null,
                        at);
                caseOpener.ensureCaseFor(projectId, recorded.findingId(), null);
            }
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

    private record FacetScope(@Nullable String callSiteId, String facet, long windowStartEpochSecond) {}

    /**
     * Unreadable degrades to unarmed rather than throwing: a malformed config must not stop detection, which cannot be
     * recomputed later.
     */
    @Nullable
    Config configOf(ClassifierRow signal) {
        if (!this.detections.writesDetections(signal.detector())) return null;
        return parse(mapper, signal.configJson());
    }

    /** The one parse both the sweep and a chart read, so the bar a chart draws is the bar that files. */
    public static @Nullable Config parse(ObjectMapper mapper, @Nullable String json) {
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

    /** The basis rides along because "17 detections" and "17 sessions" are different claims. */
    private String payload(
            String classifierKey,
            Config config,
            long observed,
            Instant start,
            Instant end,
            @Nullable String callSiteId,
            @Nullable String facet,
            @Nullable String confidence) {
        ObjectNode body = mapper.createObjectNode();
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
        return body.toString();
    }
}
