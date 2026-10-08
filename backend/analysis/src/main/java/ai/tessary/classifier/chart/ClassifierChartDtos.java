// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the Classifiers page charts. Counts and raw units only: rates are fractions, durations milliseconds,
 * cost USD. The page formats them.
 */
public final class ClassifierChartDtos {

    private ClassifierChartDtos() {}

    /** What the selectors and the Configure menu read, once per range. */
    public record ChartScopesView(
            int days,
            @JsonProperty("call_sites") List<CallSiteOption> callSites,
            List<ToolOption> tools,
            List<ClassifierMenuItem> classifiers) {

        public ChartScopesView {
            callSites = List.copyOf(callSites);
            tools = List.copyOf(tools);
            classifiers = List.copyOf(classifiers);
        }
    }

    /**
     * One call site in the selector.
     *
     * @param learning no open case, at least one rate or drift classifier on for it, and every such one still learning
     * @param turns settled traces with this call site in the range
     */
    public record CallSiteOption(
            @JsonProperty("call_site_id") String callSiteId,
            @JsonProperty("open_cases") int openCases,
            boolean learning,
            long turns) {}

    /**
     * One tool in the selector, keyed as Tool Errors keys it.
     *
     * @param calls tool calls in the range, on every call site
     * @param callers the call sites whose traces called it in the range, sorted
     */
    public record ToolOption(
            @JsonProperty("tool_key") String toolKey,
            String label,
            @JsonProperty("open_cases") int openCases,
            long calls,
            List<String> callers) {

        public ToolOption {
            callers = List.copyOf(callers);
        }
    }

    /** One classifier in the Configure menu. */
    public record ClassifierMenuItem(
            String id,
            @JsonProperty("classifier_key") String classifierKey,
            String name,
            String status,
            @JsonProperty("waiting_reason") @Nullable String waitingReason,
            String covers,
            @JsonProperty("all_call_sites") boolean allCallSites,
            @JsonProperty("call_site_count") int callSiteCount) {}

    /** The cards and chips of one call site or one tool. */
    public record ChartsView(
            String scope,
            @JsonProperty("scope_id") String scopeId,
            int days,
            @JsonProperty("from_day") String fromDay,
            @JsonProperty("to_day") String toDay,
            List<ChartCard> cards,
            List<ChartChip> chips) {

        public ChartsView {
            cards = List.copyOf(cards);
            chips = List.copyOf(chips);
        }
    }

    /**
     * One chart card.
     *
     * @param measure {@code turn_duration}, {@code tool_duration} or {@code cost} on a range card; null otherwise
     * @param learning set while no baseline is learned; always null on a count card
     * @param baseline the learned reference; null while learning and on a count card
     * @param arming the count card's bar; null on other cards and when the row has no arming block
     * @param days one entry per UTC day of the range, oldest first, the last one today
     */
    public record ChartCard(
            @JsonProperty("classifier_id") String classifierId,
            @JsonProperty("classifier_key") String classifierKey,
            String name,
            String kind,
            @Nullable String measure,
            String unit,
            @Nullable LearningView learning,
            HeadlineView headline,
            @Nullable ChartBaseline baseline,
            @Nullable ArmingView arming,
            List<ChartDay> days,
            CasesView cases) {

        public ChartCard {
            days = List.copyOf(days);
        }

        public static final String RATE = "rate";
        public static final String RANGE = "range";
        public static final String COUNT = "count";
    }

    /** How far a baseline has learned, against the minimum the detector judges from. */
    public record LearningView(long learned, long needed) {}

    /** The last seven UTC days pooled, and its distance from the baseline, in the card's unit. */
    public record HeadlineView(
            @Nullable Double value, @Nullable Double delta) {}

    /**
     * A card's baseline. A rate card sets {@code calls}, {@code failures}, {@code rate} and {@code pinned}; a range
     * card sets {@code p50} and {@code p95}. The others are null.
     *
     * @param pinned true only for a Tool Errors reference a person accepted
     */
    public record ChartBaseline(
            @Nullable Long calls,
            @Nullable Long failures,
            @Nullable Double rate,
            @Nullable Boolean pinned,
            @Nullable Double p50,
            @Nullable Double p95) {

        public static ChartBaseline rate(long calls, long failures, double rate, boolean pinned) {
            return new ChartBaseline(calls, failures, rate, pinned, null, null);
        }

        public static ChartBaseline range(double p50, double p95) {
            return new ChartBaseline(null, null, null, null, p50, p95);
        }
    }

    /** A count card's bar: {@code threshold} of {@code basis} in {@code window_seconds} opens a finding. */
    public record ArmingView(
            long threshold, @JsonProperty("window_seconds") long windowSeconds, String basis, String confidence) {}

    /**
     * One UTC day. A rate card sets {@code checked} and {@code flagged}; a range card sets {@code n}, {@code p50} and
     * {@code p95} (null on a day with no sample); a count card sets {@code count} and {@code total}. The others are
     * null.
     *
     * @param count what the arming bar counts that day: the busiest facet on the call site for Secret Leak, the
     *     whole project for a user classifier, every detection when the row has no bar
     * @param total every detection on the call site that day
     */
    public record ChartDay(
            String date,
            @Nullable Long checked,
            @Nullable Long flagged,
            @Nullable Long n,
            @Nullable Double p50,
            @Nullable Double p95,
            @Nullable Long count,
            @Nullable Long total) {

        public static ChartDay rate(String date, long checked, long flagged) {
            return new ChartDay(date, checked, flagged, null, null, null, null, null);
        }

        public static ChartDay range(String date, long n, @Nullable Double p50, @Nullable Double p95) {
            return new ChartDay(date, null, null, n, p50, p95, null, null);
        }

        public static ChartDay count(String date, long count, long total) {
            return new ChartDay(date, null, null, null, null, null, count, total);
        }
    }

    /** The cases strip: one span per finding that opened or joined a case in the range. */
    public record CasesView(@JsonProperty("open_cases") int openCases, List<CaseSpan> spans) {

        public CasesView {
            spans = List.copyOf(spans);
        }
    }

    /**
     * One finding's bar, from its onset to its case's resolution.
     *
     * @param endAt the case's {@code resolved_at}; null while the case is not resolved
     * @param disposition what a person said a resolved Frustration or Groundedness case was
     */
    public record CaseSpan(
            @JsonProperty("finding_id") String findingId,
            @JsonProperty("case_id") String caseId,
            @JsonProperty("case_reference") String caseReference,
            @JsonProperty("case_title") String caseTitle,
            @JsonProperty("case_state") String caseState,
            @JsonProperty("start_at") String startAt,
            @JsonProperty("end_at") @Nullable String endAt,
            @Nullable String resolution,
            @Nullable String disposition) {}

    /**
     * A classifier with no card for this scope.
     *
     * @param reason why it waits; set only when {@code state} is {@code waiting}
     * @param since the last UTC day with data before the range; set only when {@code state} is {@code quiet}
     */
    public record ChartChip(
            @JsonProperty("classifier_id") String classifierId,
            @JsonProperty("classifier_key") String classifierKey,
            String name,
            String state,
            @Nullable String reason,
            @Nullable String since) {}
}
