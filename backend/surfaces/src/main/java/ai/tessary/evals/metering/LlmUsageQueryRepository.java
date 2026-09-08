// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.metering;

import ai.tessary.evals.llmspi.ModelLane;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The READ half of the llm_call ledger — usage reporting.
 *
 * <p>Split from {@code usage.LlmUsageQueryRepository} (which keeps only the insert) because the two halves
 * sit at different layers: recording a call is a substrate concern every LLM caller performs, while
 * aggregating calls into a bill is a surface one. Sharing a class made every caller of the recorder
 * depend on the reporting stack.
 *
 * <p>Original: The single owner of {@code llm_call} SQL: the per-call append, and the org-scoped aggregations the
 * usage surface reads.
 *
 * <p><b>Why not {@code metric_rollup}.</b> That table meters CLOSED hour/day buckets of one
 * {@code llm_tokens} scalar — no lane, no model, no per-bucket token split — so it can say how many
 * tokens an org burned last hour and nothing about which part of the product burned them, at which
 * price, in which bucket. This ledger is written per call and read live, so the usage page is current
 * to the last call rather than lagging a bucket grain. The rollup stays the billing basis; this is the
 * breakdown.
 *
 * <p><b>Writes are fire-and-forget accounting, not the hot path's business.</b> {@link #insert} is
 * called after a completed LLM call, and {@link LlmUsageAccountant} swallows its failures — a ledger
 * outage must never fail a grading run.
 */
@Repository
public class LlmUsageQueryRepository {

    private final JdbcClient jdbc;

    public LlmUsageQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One group of the org's LLM usage — a lane, a project, a model, or (with a null {@code key}) the
     * whole org.
     *
     * <p>{@code costUsd} sums only the calls the pricing catalog held a rate for, so
     * {@code unpricedCalls} is the size of the blind spot behind it: a non-zero count means the real
     * cost is higher than the number shown, and the reader is told rather than quietly under-billed.
     * The two funding sides are carried apart — platform spend is ours, BYO spend is the customer's own
     * provider bill — and must not be added together into one figure.
     */
    public record UsageSlice(
            @Nullable String key,
            @Nullable String label,
            long calls,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long totalTokens,
            BigDecimal costUsd,
            BigDecimal platformCostUsd,
            BigDecimal byoCostUsd,
            long unpricedCalls) {}

    /**
     * One {@code (bucket, group)} cell of the usage timeseries. Carries the chartable measures only —
     * the four token buckets, their total, the call count and cost — because the funding split and the
     * unpriced-call count are read once off the window total, not per bar.
     */
    public record UsageCell(
            String bucketStart,
            String key,
            @Nullable String label,
            long calls,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long totalTokens,
            BigDecimal costUsd) {}

    /**
     * The aggregate select list every breakdown shares — the four token buckets, the derived total, the
     * cost split by funding side, and the unpriced-call count. Kept as one constant so a lane view and a
     * model view can never disagree about what "total tokens" or "cost" means.
     */
    private static final String AGGREGATES = """
            COUNT(*) AS calls,
            COALESCE(SUM(c.input_tokens), 0) AS input_tokens,
            COALESCE(SUM(c.output_tokens), 0) AS output_tokens,
            COALESCE(SUM(c.cache_read_tokens), 0) AS cache_read_tokens,
            COALESCE(SUM(c.cache_write_tokens), 0) AS cache_write_tokens,
            COALESCE(SUM(c.total_tokens), 0) AS total_tokens,
            COALESCE(SUM(c.cost_usd), 0) AS cost_usd,
            COALESCE(SUM(c.cost_usd) FILTER (WHERE c.funding = 'platform'), 0) AS platform_cost_usd,
            COALESCE(SUM(c.cost_usd) FILTER (WHERE c.funding = 'byo'), 0) AS byo_cost_usd,
            COUNT(*) FILTER (WHERE c.cost_usd IS NULL) AS unpriced_calls""";

    /**
     * The window every read shares: the org's projects, over {@code [from, to)}, optionally narrowed to
     * one lane / project / model. Either bound may be open ({@code null} → unbounded on that side),
     * matching {@code MetricRollupRepository.orgTotals}; a null filter component drops that predicate.
     *
     * <p>Lane and model are compared against {@code COALESCE(col, '')} because the empty string is a
     * real key on both axes — a call that reported no model groups under {@code ''} in {@link #byModel},
     * so filtering by that key has to match the same rows.
     */
    private static final String ORG_WINDOW = """
            FROM llm_call c
            JOIN project p ON p.id = c.project_id
            WHERE p.org_id = :orgId
              AND (:from::timestamptz IS NULL OR c.created_at >= :from::timestamptz)
              AND (:to::timestamptz IS NULL OR c.created_at < :to::timestamptz)
              AND (:lane::text IS NULL OR COALESCE(c.lane, '') = :lane::text)
              AND (:project::text IS NULL OR c.project_id = :project::text)
              AND (:model::text IS NULL OR COALESCE(c.model, '') = :model::text)""";

    /** The org's whole-period total, honouring {@code filter} — one slice with a null key. */
    public UsageSlice orgTotal(String orgId, @Nullable String from, @Nullable String to, LlmUsageFilter filter) {
        List<UsageSlice> one = query(
                "SELECT NULL::text AS key, NULL::text AS label, " + AGGREGATES + "\n" + ORG_WINDOW,
                orgId,
                from,
                to,
                filter);
        return one.isEmpty() ? empty(null) : one.get(0);
    }

    /**
     * The org's usage per lane (the product section that made the call), busiest spend first.
     *
     * <p>Unfiltered by design: this breakdown is what the usage page offers as the lane filter's
     * choices, so narrowing it by the current selection would delete every option but the chosen one.
     * The same holds for {@link #byProject} and {@link #byModel}.
     */
    public List<UsageSlice> byLane(String orgId, @Nullable String from, @Nullable String to) {
        return query(
                "SELECT c.lane AS key, NULL::text AS label, " + AGGREGATES + "\n" + ORG_WINDOW
                        + "\nGROUP BY c.lane\nORDER BY total_tokens DESC, calls DESC",
                orgId,
                from,
                to,
                LlmUsageFilter.NONE);
    }

    /** The org's usage per project — which organization the spend belongs to. Keyed by project id. */
    public List<UsageSlice> byProject(String orgId, @Nullable String from, @Nullable String to) {
        return query(
                "SELECT c.project_id AS key, p.name AS label, " + AGGREGATES + "\n" + ORG_WINDOW
                        + "\nGROUP BY c.project_id, p.name\nORDER BY total_tokens DESC, calls DESC",
                orgId,
                from,
                to,
                LlmUsageFilter.NONE);
    }

    /** The org's usage per model — the axis that explains why two lanes of equal volume cost differently. */
    public List<UsageSlice> byModel(String orgId, @Nullable String from, @Nullable String to) {
        return query(
                "SELECT COALESCE(c.model, '') AS key, NULL::text AS label, " + AGGREGATES + "\n" + ORG_WINDOW
                        + "\nGROUP BY COALESCE(c.model, '')\nORDER BY total_tokens DESC, calls DESC",
                orgId,
                from,
                to,
                LlmUsageFilter.NONE);
    }

    // ---- read: per-subject spend (what one unit of work cost) ----------------------------------

    /**
     * One unit of work and what it cost — a triage of one finding, for the launch case.
     *
     * @param subjectId the ledger's {@code subject_id}; for triage, a {@code behavior_finding} id
     * @param runs how many sandbox runs booked against it. Normally one; more than one means the finding
     *     was re-triaged, which is itself worth seeing on a spend page.
     */
    public record SubjectSpend(
            String subjectId, long runs, long totalTokens, BigDecimal costUsd, long unpricedRuns, String lastAt) {}

    /**
     * The org's spend per subject of {@code subjectKind}, costliest first — "which rulings cost what".
     *
     * <p>This is the half of launch H2 the lane alone cannot answer. `byLane` says the triage agent cost a
     * project $40 this week; this says across how many rulings, and therefore what one ruling costs — the
     * number H3's question ("is an agent session per distinct cause the right price for a filter?") turns
     * on, and the evidence H4 wants before anyone decides whether to cap.
     */
    public List<SubjectSpend> bySubject(
            String orgId, @Nullable String from, @Nullable String to, String subjectKind, int limit) {
        return jdbc.sql("SELECT c.subject_id AS subject_id,\n"
                        + "       COUNT(*) AS runs,\n"
                        + "       COALESCE(SUM(c.total_tokens), 0) AS total_tokens,\n"
                        + "       COALESCE(SUM(c.cost_usd), 0) AS cost_usd,\n"
                        + "       COUNT(*) FILTER (WHERE c.cost_usd IS NULL) AS unpriced_runs,\n"
                        + "       to_char(MAX(c.created_at) AT TIME ZONE 'UTC',"
                        + " 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS last_at\n"
                        + ORG_WINDOW
                        + "\n  AND c.subject_kind = :subjectKind AND c.subject_id IS NOT NULL"
                        + "\nGROUP BY c.subject_id\nORDER BY cost_usd DESC, runs DESC\nLIMIT :limit")
                .param("orgId", orgId)
                .param("from", from)
                .param("to", to)
                .param("lane", (String) null)
                .param("project", (String) null)
                .param("model", (String) null)
                .param("subjectKind", subjectKind)
                .param("limit", limit)
                .query((rs, n) -> new SubjectSpend(
                        rs.getString("subject_id"),
                        rs.getLong("runs"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getLong("unpriced_runs"),
                        rs.getString("last_at")))
                .list();
    }

    // ---- read: cross-org platform spend (the operator's question) ------------------------------

    /**
     * One org's PLATFORM-FUNDED spend over a window — the row the daily operator report prints.
     *
     * <p>Every other read on this repository is scoped to one org because it answers a customer's
     * question. This one answers ours, and is the only cross-org read here: launch H is done when we can
     * say "what did last week cost, and which org drove it" without opening a provider invoice, and no
     * per-org endpoint can answer a question whose subject is the comparison between orgs.
     *
     * <p>BYO spend is excluded outright rather than carried alongside: it is the customer's bill, and an
     * operator report about our costs that silently included it would overstate them.
     */
    public record OrgSpend(
            String orgId,
            String orgSlug,
            long calls,
            long totalTokens,
            BigDecimal costUsd,
            long unpricedCalls,
            long triageRuns,
            BigDecimal triageCostUsd) {}

    /**
     * Platform-funded spend per org over {@code [from, to)}, costliest first. Orgs with no platform LLM
     * activity in the window do not appear.
     */
    public List<OrgSpend> platformSpendByOrg(String from, String to, int limit) {
        return jdbc.sql("""
                        SELECT o.id AS org_id,
                               o.slug AS org_slug,
                               COUNT(*) AS calls,
                               COALESCE(SUM(c.total_tokens), 0) AS total_tokens,
                               COALESCE(SUM(c.cost_usd), 0) AS cost_usd,
                               COUNT(*) FILTER (WHERE c.cost_usd IS NULL) AS unpriced_calls,
                               COUNT(*) FILTER (WHERE c.lane = :triageLane) AS triage_runs,
                               COALESCE(SUM(c.cost_usd) FILTER (WHERE c.lane = :triageLane), 0)
                                   AS triage_cost_usd
                        FROM llm_call c
                        JOIN project p ON p.id = c.project_id
                        JOIN organization o ON o.id = p.org_id
                        WHERE c.funding = 'platform'
                          AND c.created_at >= :from::timestamptz
                          AND c.created_at < :to::timestamptz
                        GROUP BY o.id, o.slug
                        ORDER BY cost_usd DESC, total_tokens DESC
                        LIMIT :limit
                        """)
                .param("from", from)
                .param("to", to)
                .param("triageLane", ModelLane.TRIAGE.wire())
                .param("limit", limit)
                .query((rs, n) -> new OrgSpend(
                        rs.getString("org_id"),
                        rs.getString("org_slug"),
                        rs.getLong("calls"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getLong("unpriced_calls"),
                        rs.getLong("triage_runs"),
                        rs.getBigDecimal("triage_cost_usd")))
                .list();
    }

    // ---- read: bucketed timeseries (the usage chart) -------------------------------------------

    /**
     * The chartable measures per bucket. Narrower than {@link #AGGREGATES} on purpose — the funding
     * split and the unpriced-call count are window-level footnotes, not per-bar values.
     */
    private static final String CELL_AGGREGATES = """
            COUNT(*) AS calls,
            COALESCE(SUM(c.input_tokens), 0) AS input_tokens,
            COALESCE(SUM(c.output_tokens), 0) AS output_tokens,
            COALESCE(SUM(c.cache_read_tokens), 0) AS cache_read_tokens,
            COALESCE(SUM(c.cache_write_tokens), 0) AS cache_write_tokens,
            COALESCE(SUM(c.total_tokens), 0) AS total_tokens,
            COALESCE(SUM(c.cost_usd), 0) AS cost_usd""";

    /**
     * Bucket boundaries are cut in UTC ({@code created_at AT TIME ZONE 'UTC'}) rather than in whatever
     * the session timezone happens to be, so the same window returns the same bars from any connection,
     * and rendered as an explicit {@code Z} instant so {@link #seriesAxis} and the cells agree
     * character-for-character on a bucket's identity.
     */
    private static final String BUCKET_START =
            "to_char(date_trunc(:grain::text, c.created_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')";

    /**
     * The org's usage per {@code grain} bucket, cut by {@code grouping} and narrowed by {@code filter}.
     * Returns only buckets that have calls — {@link #seriesAxis} supplies the full axis, so a quiet
     * bucket renders as a gap rather than being missing.
     */
    public List<UsageCell> series(
            String orgId,
            String from,
            String to,
            LlmUsageGrain grain,
            LlmUsageGrouping grouping,
            LlmUsageFilter filter) {
        String sql = "SELECT " + BUCKET_START + " AS bucket_start,\n" + keyExpr(grouping) + " AS key,\n"
                + labelExpr(grouping) + " AS label,\n" + CELL_AGGREGATES + "\n" + ORG_WINDOW
                + "\nGROUP BY 1, 2, 3\nORDER BY 1 ASC, total_tokens DESC";
        return jdbc.sql(sql)
                .param("orgId", orgId)
                .param("from", from)
                .param("to", to)
                .param("grain", grain.wire())
                .param("lane", filter.lane())
                .param("project", filter.projectId())
                .param("model", filter.model())
                .query((rs, n) -> new UsageCell(
                        rs.getString("bucket_start"),
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cache_read_tokens"),
                        rs.getLong("cache_write_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("cost_usd")))
                .list();
    }

    /**
     * Every bucket start in {@code [from, to)} at {@code grain}, ascending — the chart's x-axis. Built
     * in SQL off the same {@code date_trunc} the cells use so a week never starts on a different day in
     * the axis than in the data.
     */
    public List<String> seriesAxis(String from, String to, LlmUsageGrain grain) {
        return jdbc.sql("""
                        SELECT to_char(g, 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS bucket_start
                        FROM generate_series(
                                date_trunc(:grain::text, (:from::timestamptz) AT TIME ZONE 'UTC'),
                                ((:to::timestamptz) AT TIME ZONE 'UTC') - interval '1 microsecond',
                                (:step)::interval) AS g
                        """)
                .param("grain", grain.wire())
                .param("from", from)
                .param("to", to)
                .param("step", grain.step())
                .query((rs, n) -> rs.getString("bucket_start"))
                .list();
    }

    /** The grouping's series key — always non-null on the wire, so the empty string carries "none of these". */
    private static String keyExpr(LlmUsageGrouping grouping) {
        return switch (grouping) {
            case NONE -> "''::text";
            case LANE -> "COALESCE(c.lane, '')";
            case PROJECT -> "c.project_id";
            case MODEL -> "COALESCE(c.model, '')";
        };
    }

    /** The grouping's display label where the server knows one the client cannot derive from the key. */
    private static String labelExpr(LlmUsageGrouping grouping) {
        return grouping == LlmUsageGrouping.PROJECT ? "p.name" : "NULL::text";
    }

    private List<UsageSlice> query(
            String sql, String orgId, @Nullable String from, @Nullable String to, LlmUsageFilter filter) {
        return jdbc.sql(sql)
                .param("orgId", orgId)
                .param("from", from)
                .param("to", to)
                .param("lane", filter.lane())
                .param("project", filter.projectId())
                .param("model", filter.model())
                .query((rs, n) -> new UsageSlice(
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cache_read_tokens"),
                        rs.getLong("cache_write_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getBigDecimal("platform_cost_usd"),
                        rs.getBigDecimal("byo_cost_usd"),
                        rs.getLong("unpriced_calls")))
                .list();
    }

    /** The all-zero slice — the shape an aggregate-over-nothing reduces to. */
    private static UsageSlice empty(@Nullable String key) {
        return new UsageSlice(key, null, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }
}
