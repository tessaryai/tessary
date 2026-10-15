// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Raw units only (fractions, milliseconds, USD); the page formats them. */
public final class ClassifierChartDtos {

    private ClassifierChartDtos() {}

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

    public record CallSiteOption(
            @JsonProperty("call_site_id") String callSiteId,
            @JsonProperty("open_cases") int openCases,
            boolean learning,
            long turns) {}

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

    public record ClassifierMenuItem(
            String id,
            @JsonProperty("classifier_key") String classifierKey,
            String name,
            String status,
            @JsonProperty("waiting_reason") @Nullable String waitingReason,
            String covers,
            @JsonProperty("all_call_sites") boolean allCallSites,
            @JsonProperty("call_site_count") int callSiteCount) {}

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

    /** @param points oldest first; only the last may be open */
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
            List<ChartPoint> points,
            CasesView cases) {

        public ChartCard {
            points = List.copyOf(points);
        }

        public static final String RATE = "rate";
        public static final String RANGE = "range";
        public static final String COUNT = "count";
    }

    public record LearningView(long learned, long needed) {}

    public record HeadlineView(
            @Nullable Double value, @Nullable Double delta) {}

    /** @param pinned true only for a Tool Errors reference a person accepted */
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

    public record ArmingView(
            long threshold, @JsonProperty("window_seconds") long windowSeconds, String basis, String confidence) {}

    /**
     * @param count what the arming bar counts: the busiest facet for Secret Leak, the whole project for a user
     * classifier
     */
    public record ChartPoint(
            @JsonProperty("start_at") String startAt,
            @JsonProperty("end_at") String endAt,
            boolean open,
            @Nullable Long checked,
            @Nullable Long flagged,
            @Nullable Long n,
            @Nullable Double p50,
            @Nullable Double p95,
            @Nullable Long count,
            @Nullable Long total,
            @Nullable Boolean reached) {

        public static ChartPoint rate(Instant start, Instant end, boolean open, long checked, long flagged) {
            return new ChartPoint(
                    start.toString(), end.toString(), open, checked, flagged, null, null, null, null, null, null);
        }

        public static ChartPoint range(
                Instant start, Instant end, boolean open, long n, @Nullable Double p50, @Nullable Double p95) {
            return new ChartPoint(start.toString(), end.toString(), open, null, null, n, p50, p95, null, null, null);
        }

        public static ChartPoint count(
                Instant start, Instant end, boolean open, long count, long total, @Nullable Boolean reached) {
            return new ChartPoint(
                    start.toString(), end.toString(), open, null, null, null, null, null, count, total, reached);
        }
    }

    public record CasesView(@JsonProperty("open_cases") int openCases, List<CaseSpan> spans) {

        public CasesView {
            spans = List.copyOf(spans);
        }
    }

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

    public record ChartChip(
            @JsonProperty("classifier_id") String classifierId,
            @JsonProperty("classifier_key") String classifierKey,
            String name,
            String state,
            @Nullable String reason,
            @Nullable String since) {}
}
