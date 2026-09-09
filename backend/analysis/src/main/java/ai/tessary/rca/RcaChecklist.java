// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.rca.RcaFacetRepository.FacetCount;
import ai.tessary.rca.RcaFacetRepository.ObservationFacet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The structural checks a finding has to be read against — measured, never judged.
 *
 * <p>Each check compares the finding's two evidence sides: the {@code baseline}-role references (what
 * things looked like before the classifier fired) against everything else (what it flagged). The sides
 * used to be two hourly windows of one grader's CUSUM replay; they are the finding's own recorded
 * substrate now, which is both narrower and honest — a reference window was always a sketch, and the
 * evidence rows are the actual traces.
 *
 * <p>These used to be deterministic <em>rule-outs</em>: threshold gates that short-circuited the
 * whole analysis with a ruling and no hypotheses. They were too blunt to be trusted — a 20% canary
 * model stamped {@code model_change} without looking at anything else.
 *
 * <p>So the thresholds are gone. Each check now emits only what it measured — full share tables — and
 * {@link AgenticRcaEngine}'s sandboxed agent decides what each one means, with MCP (always) and the repo
 * (when the project has one) available to resolve the ambiguity a bare number can't (is that new model
 * actually serving the flagged traces?). The checklist is a starting point for the investigation, not a
 * gate in front of it.
 *
 * <p><b>Three checks are gone, and the gap is real.</b> {@code grader_definition},
 * {@code grading_health} and {@code traffic_mix} all read a store that no longer exists —
 * {@code verdict} for the first two, {@code span.environment_id} for the third. "Did the instrument
 * change?" and "did the instrument break?" are genuine RCA questions with no substitute here yet;
 * re-sourcing them onto classifier detections is design work, filed as a follow-up rather than
 * improvised. What remains is {@code serving_model} and {@code failing_cohort_shape}.
 *
 * <p>{@link Measurement#check} ids are the contract the agent answers against: it returns one
 * assessment per id, and {@link RcaAnalysisService} merges the two by id. Renaming an id here
 * without updating the prompt silently drops that item's assessment.
 */
@Component
public class RcaChecklist {

    /** Facet values listed per dimension in the failing-cohort shape — enough to see a concentration
     *  without dumping a long tail the agent has to wade through. */
    private static final int TOP_FACET_VALUES = 3;

    /** One measured check: {@code check} is the id the agent assesses, {@code finding} the numbers
     *  it assesses. Deliberately carries no pass/fail — that judgment is the agent's. */
    public record Measurement(String check, String finding) {}

    private final RcaFacetRepository facets;

    public RcaChecklist(RcaFacetRepository facets) {
        this.facets = facets;
    }

    /**
     * Measure every check for one finding's two evidence sides. Order is the order the agent sees them
     * and the order they render in the report.
     */
    public List<Measurement> measure(String projectId, List<String> baselineTraceIds, List<String> flaggedTraceIds) {
        List<Measurement> out = new ArrayList<>();
        out.add(servingModel(projectId, baselineTraceIds, flaggedTraceIds));
        return out;
    }

    /** The shape of the failing cohort itself — measured after the cohort is built, so it is a
     *  separate call from {@link #measure}. */
    public Measurement failingCohortShape(String projectId, Set<String> failedTraceIds) {
        if (failedTraceIds.isEmpty()) {
            return new Measurement("failing_cohort_shape", "No flagged traces on this finding — nothing to group.");
        }
        List<ObservationFacet> rows = this.facets.observationFacets(projectId, List.copyOf(failedTraceIds));
        if (rows.isEmpty()) {
            return new Measurement(
                    "failing_cohort_shape",
                    failedTraceIds.size()
                            + " failing trace(s), but none has readable observations — the spans may have"
                            + " been deleted or never ingested.");
        }
        int traces = failedTraceIds.size();
        StringBuilder sb = new StringBuilder();
        sb.append("Top facet values across the ").append(traces).append(" failing trace(s):\n");
        appendTopValues(sb, "model", traces, tracesPerValue(rows, ObservationFacet::model));
        appendTopValues(sb, "span error", traces, tracesPerValue(rows, ObservationFacet::errorType));
        appendTopValues(
                sb,
                "failing tool",
                traces,
                tracesPerValue(
                        rows,
                        f -> "tool".equals(f.kind()) && (f.errorType() != null || "error".equals(f.status()))
                                ? f.name()
                                : null));
        return new Measurement("failing_cohort_shape", sb.toString().trim());
    }

    // ---- individual checks -------------------------------------------------------------------

    private Measurement servingModel(String projectId, List<String> baseline, List<String> flagged) {
        List<FacetCount> prior = facets.modelCounts(projectId, baseline);
        List<FacetCount> current = facets.modelCounts(projectId, flagged);
        StringBuilder sb = new StringBuilder();
        if (prior.isEmpty() && current.isEmpty()) {
            sb.append("No model-tagged traffic on either side — the serving model cannot be compared"
                    + " from telemetry. The call site's model may be readable in the repo.");
        } else {
            sb.append("Share of LLM spans by serving model.\n");
            sb.append("Baseline side: ").append(shareTable(prior)).append('\n');
            sb.append("Flagged side: ").append(shareTable(current)).append('\n');
            sb.append("A model appearing only on the flagged side may be the cause, or a canary too"
                    + " small to move the score — check whether it actually serves the failing traces.");
        }
        return new Measurement("serving_model", sb.toString());
    }

    // ---- formatting --------------------------------------------------------------------------

    private static String shareTable(List<FacetCount> counts) {
        long total = counts.stream().mapToLong(FacetCount::count).sum();
        if (total == 0) return "(no traffic)";
        return counts.stream()
                .map(c -> String.format(
                        Locale.ROOT,
                        "%s %.0f%% (%d)",
                        c.value() == null ? "(none)" : c.value(),
                        c.count() * 100.0 / total,
                        c.count()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("(no traffic)");
    }

    private static void appendTopValues(
            StringBuilder sb, String dimension, int traces, Map<String, Set<String>> byValue) {
        if (byValue.isEmpty()) return;
        String values = byValue.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Set<String>> e) ->
                                e.getValue().size())
                        .reversed())
                .limit(TOP_FACET_VALUES)
                .map(e -> String.format(
                        Locale.ROOT,
                        "'%s' on %d (%.0f%%)",
                        e.getKey(),
                        e.getValue().size(),
                        e.getValue().size() * 100.0 / traces))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        sb.append("- ").append(dimension).append(": ").append(values).append('\n');
    }

    private static Map<String, Set<String>> tracesPerValue(
            List<ObservationFacet> facets, Function<ObservationFacet, @Nullable String> key) {
        Map<String, Set<String>> byValue = new LinkedHashMap<>();
        for (ObservationFacet f : facets) {
            String value = key.apply(f);
            if (value == null || value.isBlank()) continue;
            byValue.computeIfAbsent(value, k -> new LinkedHashSet<>()).add(f.traceId());
        }
        return byValue;
    }
}
