// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorRate.Pattern;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The user-visible strings and the evidence blob a tool-error finding carries. Design contract:
 * {@code classifiers/tool_error/PROGRAM.md} §6 and §7. Modelled on {@code MetricFindingEvidence}, whose
 * conventions it keeps deliberately: round before serializing, omit an empty block rather than writing
 * one, and never let a number the UI reports be re-derived somewhere else.
 */
public final class ToolErrorEvidence {

    private ToolErrorEvidence() {}

    static final ObjectMapper JSON = new ObjectMapper();

    /** The persisted measure name. Never renamed; it is the first segment of every cause key. */
    public static final String MEASURE = "tool_error_rate";

    /**
     * Value of {@code counts_basis} on a blob whose {@code n_cur} and {@code failures.cur} span the run
     * since onset. Absent on blobs written before the onset rework, where they spanned the tool's whole
     * history since the reference was pinned.
     */
    public static final String COUNTS_BASIS_ONSET = "onset";

    /**
     * {@code tool_error_rate:<bucket_key>:<direction>}.
     *
     * <p><b>Legible on purpose, though no longer a headline.</b> The Classifiers page used to render a
     * cause key verbatim in mono as the finding's title; it now leads with {@link #title} and keeps the
     * key as detail. This stays readable anyway — it is what someone pastes into a query, and an opaque
     * id would make that step guesswork.
     *
     * <p>No reference segment, unlike metric drift's. A CUSUM has one reference (PROGRAM.md §4.6), so a
     * fourth segment could only ever hold one value and would be noise.
     */
    public static String causeKey(String bucketKey, Decision decision) {
        return MEASURE + ":" + bucketKey + ":" + decision.direction().wire();
    }

    /**
     * A sentence, not a metric: {@code "search_docs showing elevated error rates"}.
     *
     * <p><b>No rate in the headline.</b> It used to carry one, and the number it carried was the tool's
     * lifetime average, which read 6.2% during an 80% outage. Measuring it over the run fixes the number
     * but not the shape of the sentence: a percentage in a headline invites a reader to weigh two
     * findings by comparing them, and two tools' error rates are not comparable. The criticality badge is
     * what carries "how much does this matter"; {@link #basis} carries the arithmetic.
     *
     * <p>Direction survives because the down arm still runs, and a tool that stopped reporting errors has
     * either been fixed or stopped reporting.
     */
    public static String title(String bucketKey, Decision decision) {
        String movement = decision.direction() == ToolErrorDetector.Direction.UP ? "elevated" : "reduced";
        return String.format(Locale.ROOT, "%s showing %s error rates", shortName(bucketKey), movement);
    }

    /**
     * Why this crossed <b>its own</b> bar, in this detector's terms.
     *
     * <p>Triage's ranked list mixes detectors that share no threshold, so this says what the bar was
     * rather than normalizing onto someone else's scale — the posture {@code MetricDriftSource} takes for
     * the same reason.
     */
    public static String basis(Decision decision, int patternCount) {
        StringBuilder b = new StringBuilder(String.format(
                Locale.ROOT,
                "Sustained change against this tool's own in-control rate: CUSUM %.1f past a %.1f "
                        + "decision interval, over the %,d calls since onset, %s to %s (%+.2fpp).",
                decision.statistic(),
                decision.threshold(),
                decision.callsSinceOnset(),
                pct(decision.baselineRate()),
                pct(decision.currentRate()),
                decision.deltaPp()));
        if (patternCount > 0) {
            b.append(
                    patternCount == 1
                            ? " One error pattern accounts for it."
                            : " " + patternCount + " error patterns.");
        }
        return b.toString();
    }

    /**
     * Ordering weight in the cross-detector ranked list, 0..1. Never rendered as a number — the number a
     * reader sees is {@link Decision#criticality()}, and this is its squash onto the shared scale
     * {@link ai.tessary.cases.CaseDetection#severity()} requires.
     *
     * <p>Built from the accumulator rather than the effect size. Effect size answers how bad a tool is
     * per call, which does not move as an outage runs on; the ranked list is a work queue, and what
     * should rise in a work queue is the thing that has done and is doing the most damage. Criticality
     * blends severity, duration and traffic, which is the right shape for that and the wrong shape for
     * anything else.
     */
    public static double severity(Decision decision) {
        return severityOf(decision.criticality());
    }

    /** The same squash, for a case rebuilt from a persisted blob rather than a live decision. */
    public static double severityOf(double criticality) {
        double c = Math.max(0.0, criticality);
        return Math.min(1.0, c / (c + 60.0));
    }

    /** PROGRAM.md §7's blob. */
    public static String toJson(
            String bucketKey,
            Decision decision,
            List<Pattern> patterns,
            boolean patternsTruncated,
            List<String> traceIds,
            @Nullable String windowOpenedAt,
            @Nullable String windowClosedAt) {
        ObjectNode root = JSON.createObjectNode();
        root.put("measure", MEASURE);
        ObjectNode bucket = root.putObject("bucket");
        bucket.put("kind", ToolErrorBuckets.KIND);
        bucket.put("key", bucketKey);
        root.put("direction", decision.direction().wire());
        root.put("statistic", round(decision.statistic(), 3));
        root.put("threshold", round(decision.threshold(), 3));
        root.put("criticality", round(decision.criticality(), 1));
        root.put("effect_size", round(decision.effectSize(), 4));
        root.put("delta_pp", round(decision.deltaPp(), 3));

        // Stated, never inferred. Before the onset rework these same keys carried the counts since the
        // reference was pinned — a tool's whole history — and absorbing a finding pins them as the new
        // in-control rate. A reader that guessed wrong would pin a lifetime average as the normal for a
        // tool that is on fire, or a twelve-call burst as the normal for a tool that is fine. ABSENCE of
        // this key is the accurate statement that a blob predates the change; see ToolErrorEvidence.Read.
        root.put("counts_basis", COUNTS_BASIS_ONSET);
        ObjectNode rate = root.putObject("rate");
        rate.put("ref", round(decision.baselineRate(), 6));
        rate.put("cur", round(decision.currentRate(), 6));
        root.put("n_ref", decision.baselineCalls());
        root.put("n_cur", decision.callsSinceOnset());
        ObjectNode failures = root.putObject("failures");
        failures.put("cur", decision.failuresSinceOnset());

        // Ranked by what CHANGED, so the head of the list is the failure mode that moved rather than the
        // one that is merely largest. Omitted entirely when empty rather than written as [], because an
        // empty array on a finding an LLM triage agent reads is noise and its ABSENCE is the accurate
        // statement — MetricFindingEvidence's convention, kept.
        if (!patterns.isEmpty()) {
            ArrayNode arr = root.putArray("patterns");
            for (Pattern p : patterns) {
                ObjectNode n = arr.addObject();
                n.put("signature", p.signature());
                n.put("source", p.source());
                n.put("ref", p.ref());
                n.put("cur", p.cur());
            }
            // Stated rather than inferred: a truncated list must never read as the whole story.
            if (patternsTruncated) root.put("patterns_truncated", true);
        }

        // Traces the failures actually happened in. A rate is a claim about a population, and this is what
        // lets a reader — or Layer 2, which is handed this blob as its dossier — stop taking the claim on
        // trust and go and read instances of it. Omitted when empty, on the same convention as `patterns`.
        if (!traceIds.isEmpty()) {
            ArrayNode arr = root.putArray("failing_traces");
            for (String traceId : traceIds) {
                arr.add(traceId);
            }
        }

        if (decision.onsetAt() != null) root.put("onset_at", decision.onsetAt());
        ObjectNode window = root.putObject("window");
        if (windowOpenedAt != null) window.put("opened_at", windowOpenedAt);
        if (windowClosedAt != null) window.put("closed_at", windowClosedAt);
        window.put("kind", "recomputed");

        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tool error evidence failed to serialize", e);
        }
    }

    /**
     * What a case is built from, or null when the blob is unreadable.
     *
     * <p>Null rather than a zeroed record, exactly as {@code MetricFindingEvidence.read} does it: a
     * caller can then say "numbers unavailable" instead of presenting fabricated ones as the finding's.
     */
    /**
     * The parts of the blob a reader needs back out.
     *
     * <p>{@code nCur}/{@code failuresCur} are here because absorbing a finding pins them as the tool's
     * accepted reference, and the evidence is the only place they are recorded.
     *
     * @param countsAreOnsetRun whether {@code nCur}/{@code failuresCur} span the run since onset. False
     *     on a blob written before the onset rework, where they spanned the tool's whole history — and a
     *     caller that pins from one of those installs a reference the human did not agree to, so
     *     absorption refuses rather than guessing.
     * @param criticality 0 on a blob that predates the metric, which sorts such a case to the bottom
     *     rather than inventing a rank for it
     */
    public record Read(
            String bucketKey,
            double refRate,
            double curRate,
            double deltaPp,
            int patternCount,
            long nCur,
            long failuresCur,
            boolean countsAreOnsetRun,
            double criticality) {}

    public static @Nullable Read read(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(json);
            String bucketKey = root.path("bucket").path("key").asText("");
            if (bucketKey.isEmpty()) return null;
            return new Read(
                    bucketKey,
                    root.path("rate").path("ref").asDouble(0),
                    root.path("rate").path("cur").asDouble(0),
                    root.path("delta_pp").asDouble(0),
                    root.path("patterns").size(),
                    root.path("n_cur").asLong(0),
                    root.path("failures").path("cur").asLong(0),
                    COUNTS_BASIS_ONSET.equals(root.path("counts_basis").asText("")),
                    root.path("criticality").asDouble(0));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * One failure signature, then and now. The ranked list of these is what decides a rate shift: a rise
     * spread evenly across signatures that were always there is usually the traffic changing, while one
     * signature going from rare to common is the tool breaking.
     */
    public record PatternShift(String signature, String source, long ref, long cur) {}

    /**
     * The whole blob, for the surface that DRAWS a finding rather than headlines it — the counterpart of
     * {@code MetricFindingEvidence#detail}. {@link Read} stays the headline reader; this carries the
     * pattern breakdown, which is the one chart that answers "which failure took over".
     */
    public record RateDetail(
            String bucketKey,
            double refRate,
            double curRate,
            double deltaPp,
            long nRef,
            long nCur,
            long failuresCur,
            List<PatternShift> patterns,
            boolean patternsTruncated,
            List<String> failingTraces,
            @Nullable String onsetAt,
            @Nullable String windowOpenedAt,
            @Nullable String windowClosedAt) {}

    /** Parse the blob for the detail surface, or null when it cannot be read. */
    public static @Nullable RateDetail detail(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(json);
            String bucketKey = root.path("bucket").path("key").asText("");
            if (bucketKey.isEmpty()) return null;
            List<PatternShift> patterns = new ArrayList<>();
            for (JsonNode n : root.path("patterns")) {
                patterns.add(new PatternShift(
                        n.path("signature").asText(""),
                        n.path("source").asText(""),
                        n.path("ref").asLong(0),
                        n.path("cur").asLong(0)));
            }
            JsonNode window = root.path("window");
            return new RateDetail(
                    bucketKey,
                    root.path("rate").path("ref").asDouble(0),
                    root.path("rate").path("cur").asDouble(0),
                    root.path("delta_pp").asDouble(0),
                    root.path("n_ref").asLong(0),
                    root.path("n_cur").asLong(0),
                    root.path("failures").path("cur").asLong(0),
                    List.copyOf(patterns),
                    root.path("patterns_truncated").asBoolean(false),
                    failingTraces(json),
                    text(root.path("onset_at")),
                    text(window.path("opened_at")),
                    text(window.path("closed_at")));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static @Nullable String text(JsonNode node) {
        String s = node.asText("");
        return s.isEmpty() ? null : s;
    }

    /**
     * The traces the failures actually happened in, in the order written — exemplar first, then
     * witnesses. Empty when the blob is unreadable or predates the field.
     *
     * <p>Read here rather than in the case slice for the reason this class exists: a persisted shape whose
     * writer and reader live apart is a bug nothing type-checks, and {@code failing_traces} is written
     * fifty lines above. Kept off {@link Read} because that record is what a case's HEADLINE is built
     * from, and every caller of it would then carry a list it does not use.
     */
    public static List<String> failingTraces(@Nullable String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode node : JSON.readTree(json).path("failing_traces")) {
                String id = node.asText("");
                if (!id.isEmpty()) out.add(id);
            }
            return List.copyOf(out);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** {@code tool:search_docs} → {@code search_docs}. The symbol's kind prefix is noise in a sentence. */
    public static String shortName(String bucketKey) {
        int colon = bucketKey.indexOf(':');
        return colon >= 0 && colon + 1 < bucketKey.length() ? bucketKey.substring(colon + 1) : bucketKey;
    }

    private static String pct(double rate) {
        double p = rate * 100.0;
        // Sub-0.1% rates are exactly where this detector is most interesting, and "0.0%" would erase the
        // whole finding — a tool going from 0.02% to 0.9% must not read as "0.0% to 0.9%".
        return p > 0 && p < 0.1 ? String.format(Locale.ROOT, "%.2f%%", p) : String.format(Locale.ROOT, "%.1f%%", p);
    }

    private static double round(double v, int places) {
        if (!Double.isFinite(v)) return 0.0;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
