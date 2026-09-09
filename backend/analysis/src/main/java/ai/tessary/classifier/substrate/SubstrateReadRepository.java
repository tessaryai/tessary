// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import ai.tessary.classifier.detector.GroundingEvidenceReads;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The substrate read surface for the async classifier sweep: spans strictly after a sweep cursor,
 * flattened with the correlation handles they already carry, and their worst tool-error. Read-only;
 * the {@code classifier/} slice owns this repository, the same cross-feature-read pattern
 * {@code risk/} uses ({@code VerdictSignalRepository}) — ArchUnit only requires raw {@code JdbcClient}
 * to live in a {@code *Repository}, which this is.
 *
 * <h2>Nothing joins upward any more</h2>
 *
 * <p>Every read here used to climb {@code observation → trace → context turn → context session} with
 * {@code subpath(path, 0, 1)} to recover the session and the commit lineage. In v2 the span carries
 * {@code session_id}, {@code user_id}, {@code environment_id} and {@code project_version_id} as its own
 * columns (spec §3), written at ingest and repaired by the correlation backfiller for producers that
 * only tag the trace. So the spine join is not merely optimised away, it has nothing left to fetch —
 * and with it goes the class of bug where a detector silently scored zero rows because one hop of a
 * four-table chain was missing.
 *
 * <h2>The one place payloads are joined on a read path</h2>
 *
 * <p>Rule 5 of the spec says list and sweep surfaces do not touch {@code span_payload}. The detector
 * projections below are the deliberate, documented exception: a text classifier's whole input IS the
 * payload, so there is no version of this read that avoids it. What keeps it honest is that the join
 * is always BOUNDED — a keyset page of at most {@code limit} spans, or an explicit id set — never a
 * project-wide scan, and it is 1:1 on the span primary key prefix. A payload aged out by retention
 * simply yields null text, and the detectors already treat absent text as "nothing to score" rather
 * than as an empty string to score.
 *
 * <h2>The cursor is a triple now</h2>
 *
 * <p>Span identity is {@code (project_id, trace_id, id)}, so a gap-free keyset cursor has to carry all
 * three parts of {@code (created_at, trace_id, id)}. The job table has two cursor columns, not three,
 * so the two id parts ride {@code cursor_id} as the composite handle {@code "<trace_id>:<span_id>"} —
 * the same spelling M8's search results use for a span reference. {@link #parseHandle} degrades a
 * cursor it cannot read (an in-flight v1 ULID, say) to "no cursor" rather than throwing: a sweep that
 *500s on a stale cursor never advances past it, whereas one that restarts from page one converges.
 */
@Repository
public class SubstrateReadRepository implements CallSiteSchemaReads, CallSiteShapeReads, GroundingEvidenceReads {

    /**
     * Per-row cap on evidence text. The entailment head has a finite window, so one enormous retrieved
     * document would otherwise crowd out every other piece of evidence in the premise.
     */
    private static final int EVIDENCE_CHARS_PER_ROW = 4_000;

    /**
     * Cap on evidence ROWS per span, taken best-rank-first.
     *
     * <p>The per-row cap alone does not bound the premise: a long trace can retrieve dozens of
     * passages, and the serving path reads only what fits its window — {@code classify.js} chunks the
     * premise at {@code PAIR_MAX_CHUNKS=4} and reduces MAX across chunks, so roughly 6.8K characters
     * are ever actually looked at. Everything past that is not merely ignored, it is dangerous:
     * raising that chunk cap to 16 over a padded ledger was measured taking the detector from 3/3 true
     * positives to 0/3, because with enough windows something always entails the claim. Six documents
     * at 4K sits inside the readable budget, and ordering by {@code rank} means the cap drops the
     * passages the retriever itself ranked least relevant.
     */
    private static final int EVIDENCE_ROWS = 6;

    private final JdbcClient jdbc;

    public SubstrateReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A failed tool call on a span: the tool {@code name} and its {@code error} message. */
    public record ToolCallFailure(@Nullable String name, String error) {}

    /** A per-tool failure rate over a project's {@code tool_call}s: {@code failed/total} by name. */
    public record ToolErrorRate(@Nullable String toolName, long totalCalls, long failedCalls, double failureRate) {}

    /**
     * The failed tool calls on one span: {@code (name, error)} for every {@code tool_call} with a
     * non-null {@code error}. The richer-than-{@code MIN(error)} read the per-tool {@code tool_error}
     * built-in needs to carry the failing tool <em>name</em> into evidence — the shared
     * {@link #observationsAfter} projection (which all detectors share) deliberately stays unchanged, so
     * this targeted read is issued only by {@code ToolErrorRateDetector} and only for spans that already
     * carry an error (its {@code obs.toolError()} guard).
     *
     * <p>Keyed on the producer triple, never on a bare span id: {@code tool_call} rows are project-scoped
     * and their span key is only unique WITHIN a trace, so the old unscoped {@code observation_id = :oid}
     * lookup has no correct v2 spelling. Hits {@code ix_tool_call_v2_trace}.
     */
    public List<ToolCallFailure> toolCallFailures(String projectId, String traceId, String spanId) {
        return jdbc.sql("""
                SELECT name, error_type AS error FROM tool_call
                WHERE project_id = :pid AND trace_id = :tid AND span_id = :sid
                  AND error_type IS NOT NULL AND is_deleted IS NOT TRUE
                ORDER BY created_at ASC, id ASC
                """)
                .param("pid", projectId)
                .param("tid", traceId)
                .param("sid", spanId)
                .query((rs, n) -> new ToolCallFailure(rs.getString("name"), rs.getString("error")))
                .list();
    }

    /**
     * Per-tool failure rates over all of a project's {@code tool_call}s: grouped by tool {@code name},
     * {@code failed = COUNT(error)}, {@code total = COUNT(*)}, {@code rate = failed/total}. This is the
     * "rate as a derived read over the raw structured data" surface (triage shape 1): a live aggregation over
     * {@code tool_call}, not a persisted grain, so it stays consistent with the raw structure and needs no
     * migration. Ordered worst-rate-first. Note (perf): the {@code GROUP BY name} is a project-scoped scan;
     * a {@code (project_id, name)} index is a follow-up if this endpoint is ever polled by a dashboard.
     */
    public List<ToolErrorRate> toolErrorRatesByName(String projectId) {
        return jdbc.sql("""
                SELECT name                                   AS tool_name,
                       COUNT(*)                               AS total_calls,
                       COUNT(error_type)                      AS failed_calls
                FROM tool_call
                WHERE project_id = :pid
                GROUP BY name
                ORDER BY (COUNT(error_type)::float8 / COUNT(*)) DESC, name ASC
                """)
                .param("pid", projectId)
                .query((rs, n) -> {
                    long total = rs.getLong("total_calls");
                    long failed = rs.getLong("failed_calls");
                    double rate = total == 0 ? 0.0 : (double) failed / total;
                    return new ToolErrorRate(rs.getString("tool_name"), total, failed, rate);
                })
                .list();
    }

    /**
     * Count of substrate spans for a project — the browser-reachable "has a live trace landed?"
     * detector the onboarding flow polls. Live OTLP traffic writes {@code span} rows via the substrate
     * write path; the offline upload/sample path creates a graded {@code run} instead and writes
     * <em>no</em> substrate, so the presence of any row here is an unambiguous signal that a
     * <em>live</em> trace arrived (never the sample fallback). This is an onboarding liveness check,
     * not a general query surface — the filtered/aggregated substrate read lives behind the token-scoped
     * {@code POST /v1/query/count}. The onboarding poll only needs liveness, so this short-circuits on the
     * first matching row ({@code EXISTS}) rather than scanning to a full count.
     */
    public boolean hasSpans(String projectId) {
        Boolean live = jdbc.sql("SELECT EXISTS(SELECT 1 FROM span WHERE project_id = :pid)")
                .param("pid", projectId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(live);
    }

    /**
     * Per-UTC-day counts of non-deleted traces started since {@code from}, oldest day first — the
     * denominator for "what % of traces did each classifier flag". Buckets on
     * {@code (started_at AT TIME ZONE 'UTC')::date} to pin day boundaries to UTC regardless of the
     * session timezone.
     *
     * <p>Bucketed on EVENT time, not ingest time, and that is a behaviour change worth naming: v1
     * counted {@code trace.created_at} because the trace row had one, and a backfilled corpus therefore
     * piled a month of traffic into the hour it was uploaded. {@code trace} has no {@code created_at}
     * at all (spec §5.1) — the row is identity plus rollups, and when it was written is not a fact about
     * the turn. Index-served by {@code ix_trace_project_started}.
     */
    public List<DailyTraceCount> dailyTraceCounts(String projectId, java.time.Instant from) {
        return jdbc.sql("""
                SELECT (started_at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS total
                FROM trace
                WHERE project_id = :pid AND started_at >= :from AND is_deleted IS NOT TRUE
                GROUP BY day
                ORDER BY day ASC
                """)
                .param("pid", projectId)
                .param("from", java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC))
                .query((rs, n) ->
                        new DailyTraceCount(rs.getObject("day", java.time.LocalDate.class), rs.getLong("total")))
                .list();
    }

    /** Non-deleted traces started in one UTC day bucket. */
    public record DailyTraceCount(java.time.LocalDate day, long total) {}

    /**
     * Count of a project's spans that carry no {@code call_site_id} — ingested spans that are
     * invisible to every call-site-scoped feature (grader generation, coverage) until the producing code
     * tags them with {@code tessary.call_site.id}. Powers the onboarding/pipeline "instrument your call
     * sites" nudge, so the count is capped at {@value #UNTAGGED_CAP} via a LIMIT subquery: the UI renders
     * "{cap}+" beyond it and an exact count over an unbounded substrate is never paid for a banner.
     */
    public long untaggedSpans(String projectId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM (
                    SELECT 1 FROM span
                    WHERE project_id = :pid AND call_site_id IS NULL
                    LIMIT :cap
                ) capped
                """)
                .param("pid", projectId)
                .param("cap", UNTAGGED_CAP)
                .query(Long.class)
                .single();
    }

    /** The cap on {@link #untaggedSpans}: past this the exact number no longer changes the nudge. */
    public static final int UNTAGGED_CAP = 1000;

    /**
     * Whether the project has ever landed a span carrying {@code tessary.call_site.id} — the connect
     * gate's redirect signal (#1227). {@code ix_span_call_site} is a partial index on exactly this
     * predicate ({@code WHERE call_site_id IS NOT NULL}), so this is index-served, not a scan.
     *
     * <p>Deliberately separate from {@link ai.tessary.onboarding.OnboardingRepository#trafficWindow},
     * which advances the onboarding ladder's listening→fitting rung on ANY span, tagged or not — that is
     * epic 7's own gate-proving instrument (tessary-paid/OPEN-CORE.md, epic 7 clause 5) and this method must never
     * change what it measures. The connect gate is a stricter, additive question: not merely "has
     * traffic arrived" but "has traffic arrived that classifiers can actually attribute to a call site".
     */
    public boolean hasTaggedSpan(String projectId) {
        Boolean tagged = jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM span WHERE project_id = :pid AND call_site_id IS NOT NULL)")
                .param("pid", projectId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(tagged);
    }

    /**
     * Total spans received so far, capped at {@link #UNTAGGED_CAP} for the same reason {@link
     * #untaggedSpans} is: the connect gate's untagged-wait-state stat row wants a live count, not an
     * exact one past the point where the number stops changing what the screen says.
     */
    public long spansReceived(String projectId) {
        return jdbc.sql("SELECT COUNT(*) FROM (SELECT 1 FROM span WHERE project_id = :pid LIMIT :cap) capped")
                .param("pid", projectId)
                .param("cap", UNTAGGED_CAP)
                .query(Long.class)
                .single();
    }

    /** Tagged spans received so far — the untagged wait state's "Tagged" stat, capped the same way. */
    public long taggedSpans(String projectId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM (
                    SELECT 1 FROM span
                    WHERE project_id = :pid AND call_site_id IS NOT NULL
                    LIMIT :cap
                ) capped
                """)
                .param("pid", projectId)
                .param("cap", UNTAGGED_CAP)
                .query(Long.class)
                .single();
    }

    /**
     * Event time of the most recently arrived span, or {@code null} before any — the untagged wait
     * state's "Last span" stat. Index-served by {@code ix_span_project_started}'s descending end.
     */
    public @Nullable String lastSpanAt(String projectId) {
        return jdbc.sql("""
                SELECT to_char(MAX(started_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                FROM span WHERE project_id = :pid
                """)
                .param("pid", projectId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /**
     * The service name off the single most recent span that reported one — the untagged wait state's
     * "Service" stat. A single most-recent-row read, NOT an aggregate: {@code span} has no dedicated
     * service-name column (spec §3 never gave it one — a service is an attribute a producer sends, not
     * a substrate concept), so the only source is {@code span_payload.attributes}, and scanning every
     * row for a majority vote would be exactly the kind of project-wide {@code span_payload} touch
     * Rule 5 forbids list/sweep surfaces from taking. One row, ordered by the same
     * {@code ix_span_project_started} index {@link #lastSpanAt} already uses, is enough for a stat that
     * only has to be roughly right for the newest traffic.
     */
    public @Nullable String recentServiceName(String projectId) {
        return jdbc.sql("""
                SELECT pl.attributes ->> 'service.name'
                FROM span s
                JOIN span_payload pl
                  ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id
                WHERE s.project_id = :pid AND pl.attributes ->> 'service.name' IS NOT NULL
                ORDER BY s.started_at DESC
                LIMIT 1
                """)
                .param("pid", projectId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** The declared output schemas of a batch's call sites — the Malformed Output built-in's read. */
    @Override
    public java.util.Map<String, String> callSiteOutputSchemas(String projectId, java.util.Set<String> callSiteIds) {
        if (callSiteIds.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        jdbc.sql("SELECT id, output_schema FROM call_site "
                        + "WHERE project_id = :pid AND id IN (:ids) AND output_schema IS NOT NULL")
                .param("pid", projectId)
                .param("ids", callSiteIds)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("output_schema")))
                .list();
        return java.util.Map.copyOf(out);
    }

    /** The declared shape of a batch's call sites — the Groundedness built-in's gating read. */
    @Override
    public java.util.Map<String, String> callSiteShapes(String projectId, java.util.Set<String> callSiteIds) {
        if (callSiteIds.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.HashMap<>();
        jdbc.sql("SELECT id, shape FROM call_site " + "WHERE project_id = :pid AND id IN (:ids) AND shape IS NOT NULL")
                .param("pid", projectId)
                .param("ids", callSiteIds)
                .query((rs, n) -> out.put(rs.getString("id"), rs.getString("shape")))
                .list();
        return java.util.Map.copyOf(out);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Subjects are addressed as {@code (traceId, spanId)} pairs and the returned map is keyed by the
     * span id, because that is the handle the detector holds. Within one trace a span id is unique, and
     * every caller passes one trace's spans or an explicitly-paired set, so the key is unambiguous.
     *
     * <p>Retrieved documents only, scoped to the CONVERSATION and to what came BEFORE the answer — a
     * retrieval runs on a sibling span, not on the answering LLM span, so scoping to the span itself
     * finds nothing on the agent shape this exists for. Conversation scope, not trace scope: a
     * multi-turn agent commonly retrieves once and answers several follow-ups from that same context
     * without re-retrieving, and each turn here is its own trace, so trace-only scope left every
     * follow-up scored against the bare question it was asked (near-zero support, near-universal false
     * fire — measured 88% on stale-context follow-ups against an 8.6% same-trace baseline, over a
     * 500-case synthetic eval). Grouping key is {@code COALESCE(trace.thread_id, trace.session_id)},
     * the same key {@link #conversationObservationsUpTo} uses for conversation-grain reads — including
     * its null fallback: a trace with neither id set groups with itself only, never with every other
     * unset-id trace in the project.
     *
     * <p><b>Nearest-prior-retrieval, not a conversation-wide blend.</b> A conversation's topic can shift
     * turn to turn, so ranking every retrieved_doc candidate across the whole conversation together
     * risked handing a follow-up a premise stitched from unrelated earlier retrievals — a different
     * false-positive/false-negative source than the one being fixed. Instead: resolve the single
     * NEAREST prior trace in the conversation that has retrieved_doc rows (the span's own trace first,
     * identical to the old behavior, else walking backward), then run the unchanged per-trace
     * extraction against that one resolved trace. One retrieval event's worth of evidence, same size as
     * the original single-trace case, never several turns' worth blended.
     *
     * <p><b>The time bound is load-bearing, not hygiene.</b> A trace can interleave several LLM turns
     * with several retrievals, and unbounded scope judges an early turn against documents fetched after
     * it — measured on 29 of 112 evidence-receiving spans before this was bounded. That is not a
     * harmless over-collection: the serving path reduces MAX across premise chunks, so a fabricated
     * claim that happens to be entailed by a LATER passage scores supported and a real hallucination
     * goes unreported. It also robs a pre-retrieval turn of the prompt fallback it should have had. The
     * comparison uses each span's own {@code started_at}, which v2 makes NOT NULL, so no fallback is
     * needed.
     *
     * <p><b>Tool results are deliberately not evidence.</b> They were, and the entailment head could
     * not read them: measured over 84 real tool-backed answers the detector fired on 84, every one a
     * false positive, and neither claim decomposition nor hand-written key verbalisation moved it
     * below 79%. MiniCheck is trained on natural-language premises and a JSON result object is not
     * one. Leaving the rows out rather than adding a carrier flag is what makes the existing BLIND
     * rule cover this for free — a tool-only trace reaches outside, captures no readable evidence, and
     * is abstained instead of scored. Re-enabling means restoring this arm AND a verbalisation step
     * that turns a result object into prose; the arm alone is what we already measured failing.
     *
     * <p>Trace scope admits two rows the span scope excluded by accident. A rerank stage re-emits its
     * survivors, so the same passage arrives twice; content is de-duplicated. And a reranker's
     * discarded inputs are recorded as {@code list_role = 'candidate'} — grounding an answer in a
     * passage the pipeline THREW AWAY would read as supported, so explicit candidates are dropped.
     * Only explicit ones: a producer that leaves {@code list_role} null is kept, because the failure of
     * silently dropping real evidence is worse than the failure of admitting a rejected passage.
     * Content is capped per row so one enormous document cannot dominate a premise the entailment head
     * has a finite window for.
     */
    @Override
    public java.util.Map<String, GroundingEvidenceReads.Evidence> groundingEvidence(
            String projectId, java.util.Set<GroundingEvidenceReads.SpanRef> spans) {
        if (spans.isEmpty()) return java.util.Map.of();
        List<String> traceIds = spans.stream()
                .map(GroundingEvidenceReads.SpanRef::traceId)
                .distinct()
                .toList();
        List<String> spanIds = spans.stream()
                .map(GroundingEvidenceReads.SpanRef::spanId)
                .distinct()
                .toList();
        java.util.Map<String, StringBuilder> acc = new java.util.HashMap<>();
        java.util.Set<String> reachedOutside = new java.util.HashSet<>();
        // CONVERSATION scope, not trace scope: a follow-up turn that reuses an earlier turn's retrieval
        // without re-retrieving is a genuinely BLIND-vs-GROUNDLESS question about the whole conversation,
        // not just this one trace — a multi-turn agent that retrieves once and answers several follow-ups
        // from that context is the common case, and scoring those against the bare follow-up question
        // reproduced the same "premise cannot contain the evidence" failure this method exists to avoid.
        // Grouping key mirrors ConversationThreadAssembler/conversationObservationsUpTo's
        // COALESCE(parent_id, id) exactly, so this reaches the same conversation a dialogue-thread read
        // would. Deliberately NOT time-bounded, same as the pre-existing trace-scoped behavior
        // (documentsRetrievedAfterTheAnswerAreNotEvidenceForIt pins this): a call site that reaches
        // outside ANYWHERE in the conversation, even later, still reads BLIND rather than GROUNDLESS —
        // abstaining is safer than confidently scoring against a bare prompt for a call site we know does
        // retrieval-shaped work at all. Only the EVIDENCE TEXT below is time-bounded, so this never lets
        // a future document become a premise, it only decides abstain-vs-fallback-to-prompt.
        jdbc.sql("""
                SELECT s.id AS span_id
                FROM span s
                  JOIN trace tr ON tr.project_id = s.project_id AND tr.id = s.trace_id
                WHERE s.project_id = :pid AND s.trace_id IN (:tids) AND s.id IN (:sids)
                  AND s.is_deleted IS NOT TRUE
                  AND EXISTS (SELECT 1 FROM span x
                               JOIN trace xtr ON xtr.project_id = x.project_id AND xtr.id = x.trace_id
                               WHERE x.project_id = s.project_id AND x.is_deleted IS NOT TRUE
                                 AND (
                                   COALESCE(xtr.thread_id, xtr.session_id) = COALESCE(tr.thread_id, tr.session_id)
                                   OR (x.trace_id = s.trace_id AND COALESCE(tr.thread_id, tr.session_id) IS NULL)
                                 )
                                 -- the same set ConversationThreadAssembler treats as external work;
                                 -- omitting one makes its traces read GROUNDLESS instead of BLIND
                                 AND x.kind IN ('tool','mcp','retrieval','reranker','embedding'))
                """)
                .param("pid", projectId)
                .param("tids", traceIds)
                .param("sids", spanIds)
                .query((rs, n) -> reachedOutside.add(rs.getString("span_id")))
                .list();
        // Sliding window of size ONE prior retrieval, not a conversation-wide blend: a topic can shift
        // across turns (retrieve shipping info turn 2, retrieve returns info turn 15), and ranking
        // retrieved_doc candidates from anywhere in the conversation together would risk handing a
        // follow-up a premise stitched from unrelated earlier retrievals. Resolve the NEAREST prior trace
        // in the same conversation that has retrieved_doc rows first (own trace if it has any — identical
        // to the pre-existing behavior — else walking backward), then run the exact same per-trace
        // extraction this method always ran, just against that resolved trace instead of always
        // `o.trace_id`. One retrieval event's worth of content either way, never several blended.
        jdbc.sql("""
                SELECT s.id AS span_id, e.txt AS txt, e.ord AS ord
                FROM span s
                  JOIN trace tr ON tr.project_id = s.project_id AND tr.id = s.trace_id
                  JOIN LATERAL (
                      SELECT rd2.trace_id AS trace_id
                        FROM retrieved_doc rd2
                        JOIN span r2 ON r2.project_id = rd2.project_id AND r2.trace_id = rd2.trace_id
                                     AND r2.id = rd2.span_id AND r2.is_deleted IS NOT TRUE
                        JOIN trace r2tr ON r2tr.project_id = r2.project_id AND r2tr.id = r2.trace_id
                       WHERE rd2.project_id = s.project_id AND rd2.is_deleted IS NOT TRUE
                         AND (
                           COALESCE(r2tr.thread_id, r2tr.session_id) = COALESCE(tr.thread_id, tr.session_id)
                           OR (r2.trace_id = s.trace_id AND COALESCE(tr.thread_id, tr.session_id) IS NULL)
                         )
                         AND r2.started_at <= s.started_at
                       ORDER BY r2.started_at DESC
                       LIMIT 1
                  ) nearest ON TRUE
                  JOIN LATERAL (
                      SELECT d.txt, d.ord
                        FROM (
                          SELECT DISTINCT ON (rd.content)
                                 left(rd.content, :cap) AS txt,
                                 COALESCE(rd.rank, rd.seq, 0) AS ord
                            FROM retrieved_doc rd
                            JOIN span r
                              ON r.project_id = rd.project_id AND r.trace_id = rd.trace_id
                             AND r.id = rd.span_id AND r.is_deleted IS NOT TRUE
                           WHERE rd.project_id = s.project_id AND rd.trace_id = nearest.trace_id
                             AND rd.is_deleted IS NOT TRUE
                             AND COALESCE(rd.list_role, 'result') <> 'candidate'
                             AND rd.content IS NOT NULL AND rd.content <> ''
                             AND r.started_at <= s.started_at
                           ORDER BY rd.content, COALESCE(rd.rank, rd.seq, 0)
                        ) d
                       -- content breaks rank ties: two retrieval spans in one trace both rank their
                       -- own results from 0, and an unstable sort there would hand the same turn a
                       -- different premise on a re-score
                       ORDER BY d.ord, d.txt
                       LIMIT :rows
                  ) e ON TRUE
                WHERE s.project_id = :pid AND s.trace_id IN (:tids) AND s.id IN (:sids)
                  AND s.is_deleted IS NOT TRUE
                ORDER BY s.id, e.ord, e.txt
                """)
                .param("pid", projectId)
                .param("tids", traceIds)
                .param("sids", spanIds)
                .param("cap", EVIDENCE_CHARS_PER_ROW)
                .param("rows", EVIDENCE_ROWS)
                .query((rs, n) -> {
                    String txt = rs.getString("txt");
                    if (txt != null && !txt.isBlank()) {
                        acc.computeIfAbsent(rs.getString("span_id"), k -> new StringBuilder())
                                .append(txt)
                                .append('\n');
                    }
                    return Boolean.TRUE; // the row mapper's value is unused; the accumulator is the result
                })
                .list();
        java.util.Map<String, GroundingEvidenceReads.Evidence> out = new java.util.HashMap<>();
        for (GroundingEvidenceReads.SpanRef ref : spans) {
            String id = ref.spanId();
            StringBuilder sb = acc.get(id);
            String text = sb == null ? "" : sb.toString().strip();
            boolean outside = reachedOutside.contains(id);
            if (!text.isEmpty() || outside) out.put(id, new GroundingEvidenceReads.Evidence(text, outside));
        }
        return java.util.Map.copyOf(out);
    }

    /** Distinct projects that have at least one substrate span — the worker's sweep scope. */
    public List<String> projectsWithObservations() {
        return jdbc.sql("SELECT DISTINCT project_id FROM span")
                .query((rs, n) -> rs.getString(1))
                .list();
    }

    /**
     * Spans for {@code projectId} strictly after the keyset cursor {@code (afterTs, afterHandle)} —
     * i.e. {@code (s.created_at, s.trace_id, s.id) > (afterTs, afterTraceId, afterSpanId)} — ordered by
     * that same triple so the cursor advances monotonically and gap-free, capped at {@code limit}. When
     * the cursor is null (first sweep) all spans are returned.
     *
     * <p>The id tiebreakers are what keep the sweep from skipping spans that share an identical
     * {@code created_at} across a batch boundary; a {@code created_at}-only {@code >} cursor would drop
     * them. Both id parts are needed because a span id is unique only within its trace, so the pair is
     * the identity — a bare span-id tiebreak could order two different spans arbitrarily and skip one.
     *
     * @param afterHandle the {@code "<trace_id>:<span_id>"} composite handle stamped alongside
     *     {@code created_at} by the previous sweep, or null on the first one. A handle this method
     *     cannot parse is treated as absent (see the class javadoc).
     */
    public List<SubstrateObservation> observationsAfter(
            String projectId, @Nullable String afterTs, @Nullable String afterHandle, int limit) {
        return spec(SELECT_SPAN, projectId, afterTs, afterHandle, limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** One row of a TURN-grain candidate window: the span, plus whether it is a turn ROOT. */
    public record TurnCandidate(SubstrateObservation observation, boolean turnRoot) {}

    /**
     * The TURN-GRAIN candidate window for classifiers declaring
     * {@link ClassifierModelModule.Grain#TURN} (frustration). Deliberately the same UNFILTERED window
     * {@link #observationsAfter} draws, with the turn-root predicate carried as a projected boolean the
     * worker filters in Java.
     *
     * <p><b>Why the predicate is not a WHERE clause.</b> The cursor advances over the window. A
     * filtered window is empty for any project whose traces are all nested under a parent, or whose
     * spans are never {@code llm}/{@code agent} — and an empty window advances no cursor, so every tick
     * re-scans the whole unswept history forever. Projecting the predicate keeps the window exactly
     * {@code limit} rows wide, so the cursor always moves.
     *
     * <p><b>What "user-facing" means structurally.</b> Kind is NOT the discriminator — {@code
     * gen_ai.operation.name = invoke_agent} normalizes to {@code agent} at every nesting depth, so a
     * sub-agent's span is an {@code agent} span too. Three structural facts define the turn root:
     * <ul>
     *   <li>{@code tr.parent_trace_id IS NULL} — the trace is not a sub-agent trace nested under a caller.
     *   <li>{@code s.parent_span_id IS NULL} — the span is its trace's outermost, so its input/output
     *       are the turn's aggregate I/O rather than an inner planner/summarizer call's. In v2 this is
     *       the producer's own statement, stored verbatim and never repaired, so a null here means the
     *       producer said ROOT rather than "the parent has not arrived yet" (that is a null {@code path}).
     *   <li>{@code s.kind IN ('llm','agent')} — it carries dialogue at all. This deliberately does not
     *       require {@code agent}: a product with no agent wrapper emits a bare {@code llm} root, and that
     *       root IS its user-facing turn.
     * </ul>
     *
     * <p>A turn with several parentless root spans yields several rows; the worker keeps one per
     * {@code trace_id} and advances the cursor on the raw window's last row.
     */
    public List<TurnCandidate> turnCandidatesAfter(
            String projectId, @Nullable String afterTs, @Nullable String afterHandle, int limit) {
        return spec(SELECT_TURN_CANDIDATE, projectId, afterTs, afterHandle, limit)
                .query((rs, n) -> new TurnCandidate(map(rs), rs.getBoolean("turn_root")))
                .list();
    }

    /**
     * The most-recent {@code limit} spans for a project, newest first — the bounded sample the
     * classifier cold-start labeling pass draws from. Bounded by COUNT, never by truncating the span
     * text (the labeling judge sees full input/output). Reuses the same projection as the sweep so an
     * example is featurized identically to how it is scored.
     */
    public List<SubstrateObservation> sampleObservations(String projectId, int limit) {
        return jdbc.sql(SELECT_SPAN
                        + " WHERE s.project_id = :pid"
                        + " ORDER BY s.created_at DESC, s.trace_id DESC, s.id DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The scored span's CONVERSATION thread: the most-recent {@code limit} spans sharing the scored
     * span's conversation grain, at or before the scored span's keyset position, newest-first. The
     * {@link ConversationThreadAssembler} reverses this to chronological order, drops the scored turn,
     * and renders the rest as prior turns.
     *
     * <p><b>Grouping grain.</b> The conversation is the pinned {@code COALESCE(trace.thread_id,
     * trace.session_id)} (implementation plan §3): the producer's own thread id when it sent one, else
     * the session. Sessions do not nest in v2 and a conversation is a column rather than a second tree
     * level, so there is no depth to get wrong — the v1 bug this replaces was grouping on the session
     * root (depth 0) when the conversation tier (depth 1) was the right level, a mistake the shape
     * itself now makes unavailable.
     *
     * <p>A trace with neither a thread nor a session id has a NULL conversation key, and the predicate
     * is written in two explicit branches because of it. The equality arm matches nothing when the
     * scored key is NULL (SQL equality on NULL is unknown, not true), and the second arm then matches
     * the scored trace alone — so an anonymous turn is its own single-turn conversation. Writing it as
     * {@code IS NOT DISTINCT FROM} instead would make NULL match NULL and hand that turn the whole
     * project's anonymous history as its thread.
     *
     * <p>Ordering is the same {@code (created_at, trace_id, id)} keyset the sweep cursor uses, so turns
     * within a conversation order deterministically even when several spans share a {@code created_at}.
     * Both conversational spans ({@code kind in (llm, agent)}) AND tool/retrieval spans ({@code tool,
     * mcp, retrieval, embedding, reranker}) are returned: the assembler renders one dialogue
     * contribution per turn and each tool span as a terse OUTCOME marker, so the agent's failure history
     * stays visible without the raw payloads polluting the thread. Kept in sync with
     * {@code ConversationThreadAssembler.TOOL_KINDS}. Non-outcome control spans (reasoning/workflow/…)
     * stay excluded. Perf: one read per scored span on the async sweep (never the ingest hot path);
     * the conversation lookup is a primary-key read on {@code trace}.
     */
    public List<SubstrateObservation> conversationObservationsUpTo(
            String projectId, String scoredTraceId, String scoredSpanId, String uptoTs, int limit) {
        return jdbc.sql(SELECT_SPAN + """

                        JOIN trace tr ON tr.project_id = s.project_id AND tr.id = s.trace_id
                        WHERE s.project_id = :pid
                          AND (
                            -- The scored trace's conversation, when it has one …
                            COALESCE(tr.thread_id, tr.session_id) = (
                                SELECT COALESCE(str.thread_id, str.session_id) FROM trace str
                                 WHERE str.project_id = :pid AND str.id = :scoredTraceId)
                            -- … else the scored trace alone. An anonymous turn is its own conversation;
                            -- pooling every session-less trace in the project would be a thread made of
                            -- unrelated strangers.
                            OR (s.trace_id = :scoredTraceId
                                AND (SELECT COALESCE(str.thread_id, str.session_id) FROM trace str
                                      WHERE str.project_id = :pid AND str.id = :scoredTraceId) IS NULL)
                          )
                          AND s.kind IN ('llm', 'agent', 'tool', 'mcp', 'retrieval', 'embedding', 'reranker')
                          AND (s.created_at, s.trace_id, s.id) <= (:uptoTs::timestamptz, :scoredTraceId, :scoredSpanId)
                        ORDER BY s.created_at DESC, s.trace_id DESC, s.id DESC
                        LIMIT :limit""")
                .param("pid", projectId)
                .param("scoredTraceId", scoredTraceId)
                .param("scoredSpanId", scoredSpanId)
                .param("uptoTs", uptoTs)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** One span by its producer identity within a project (the correction loop labels a specific subject). */
    public java.util.Optional<SubstrateObservation> observationById(String projectId, String traceId, String spanId) {
        return jdbc.sql(SELECT_SPAN + " WHERE s.project_id = :pid AND s.trace_id = :tid AND s.id = :sid")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("sid", spanId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * The shared keyset-page builder for the two sweep windows. Branches on the cursor rather than
     * binding null parameters into the row-value comparison: Postgres cannot infer the type of untyped
     * null binds in that position ("could not determine data type of parameter"), so the keyset
     * predicate is added only when there is a cursor.
     */
    private JdbcClient.StatementSpec spec(
            String select, String projectId, @Nullable String afterTs, @Nullable String afterHandle, int limit) {
        Cursor cursor = afterTs == null ? null : parseHandle(afterHandle);
        String cursorClause = cursor == null
                ? ""
                : " AND (s.created_at, s.trace_id, s.id) > (:afterTs::timestamptz, :afterTraceId, :afterSpanId)";
        var spec = jdbc.sql(select + " WHERE s.project_id = :pid" + cursorClause
                        + " ORDER BY s.created_at ASC, s.trace_id ASC, s.id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit);
        if (cursor != null) {
            spec = spec.param("afterTs", afterTs)
                    .param("afterTraceId", cursor.traceId())
                    .param("afterSpanId", cursor.spanId());
        }
        return spec;
    }

    /** The two id halves of a parsed cursor handle. */
    record Cursor(String traceId, String spanId) {}

    /**
     * Split a {@code "<trace_id>:<span_id>"} cursor handle, or null when it is not one.
     *
     * <p>Null in, null out — and the same for a handle stamped by a release that stored a bare surrogate
     * observation id. A sweep that threw on a stale cursor would never get past it; one that restarts
     * from page one re-scores rows whose verdict upserts are idempotent and then converges. Split on the
     * LAST colon so a producer trace id containing one is still parsed correctly.
     */
    static @Nullable Cursor parseHandle(@Nullable String handle) {
        if (handle == null) return null;
        int cut = handle.lastIndexOf(':');
        if (cut <= 0 || cut == handle.length() - 1) return null;
        return new Cursor(handle.substring(0, cut), handle.substring(cut + 1));
    }

    /** The composite cursor handle for one span — the inverse of {@link #parseHandle}. */
    public static String handle(String traceId, String spanId) {
        return traceId + ":" + spanId;
    }

    /**
     * The shared span projection: the span's own columns, its payload text, and its worst tool error.
     *
     * <p>The payload join is the documented rule-5 exception (see the class javadoc) and is LEFT so a
     * span whose payload retention has expired still appears — with null text, which the detectors read
     * as "nothing to score" rather than as an empty string worth scoring.
     */
    private static final String SPAN_COLUMNS = """
            SELECT s.id                 AS span_id,
                   s.project_id         AS project_id,
                   s.trace_id           AS trace_id,
                   s.session_id         AS session_id,
                   s.project_version_id AS project_version_id,
                   s.call_site_id       AS call_site_id,
                   s.kind               AS kind,
                   s.name               AS name,
                   pl.input             AS input,
                   pl.output            AS output,
                   (SELECT MIN(tc.error_type) FROM tool_call tc
                      WHERE tc.project_id = s.project_id AND tc.trace_id = s.trace_id
                        AND tc.span_id = s.id AND tc.error_type IS NOT NULL) AS tool_error,
                   s.created_at         AS created_at""";

    /** The turn-root predicate, projected rather than filtered — see {@link #turnCandidatesAfter}. */
    private static final String TURN_ROOT_COLUMN = """
            ,
                   (EXISTS (SELECT 1 FROM trace tr
                             WHERE tr.project_id = s.project_id AND tr.id = s.trace_id
                               AND tr.parent_trace_id IS NULL)
                    AND s.parent_span_id IS NULL
                    AND s.kind IN ('llm', 'agent'))                              AS turn_root""";

    private static final String SPAN_FROM = """

            FROM span s
              LEFT JOIN span_payload pl
                ON pl.project_id = s.project_id AND pl.trace_id = s.trace_id AND pl.span_id = s.id""";

    private static final String SELECT_SPAN = SPAN_COLUMNS + SPAN_FROM;

    private static final String SELECT_TURN_CANDIDATE = SPAN_COLUMNS + TURN_ROOT_COLUMN + SPAN_FROM;

    private static SubstrateObservation map(ResultSet rs) throws SQLException {
        return new SubstrateObservation(
                rs.getString("span_id"),
                rs.getString("project_id"),
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("project_version_id"),
                rs.getString("call_site_id"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getString("input"),
                rs.getString("output"),
                rs.getString("tool_error"),
                ai.tessary.storage.Timestamps.iso(rs, "created_at"));
    }
}
