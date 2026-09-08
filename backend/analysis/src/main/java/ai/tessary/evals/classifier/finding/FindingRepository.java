// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for {@code finding} — the one table every classifier files into.
 *
 * <p><b>Every upsert here conflicts on the SAME arbiter</b>, {@code ux_finding_live} over
 * {@code (project_id, classifier_key, cause_key)} with the predicate {@code status IN ('open',
 * 'blocked')}. That is the whole point of composing scope into {@link CauseKey}: the three writers used
 * to conflict on three different partial indexes, and Postgres infers the arbiter from the conflict
 * target, so one method could not name them all. The predicate is restated character for character in
 * each statement because a partial index is matched by its expression.
 *
 * <p><b>'blocked' stays live, and dropping it re-introduces 0033's bug class.</b> A blocked row that
 * left the index would conflict with nothing, the next firing would INSERT beside it, {@code onset_at}
 * would reset, and the human verdict would be silently discarded.
 *
 * <p>Each {@code record*} returns whether THIS call created the finding: a new finding escalates once,
 * on its exemplar, while the 499 later traces firing on the same cause only bump a counter. That single
 * rule is both the UX fix and the cost fix.
 */
@Repository
public class FindingRepository {

    private static final String COLS = "id, project_id, classifier_key, cause_key, subject_kind, subject_id, "
            + "subject_label, call_site_id, status, onset_at, last_seen_at, title, basis, severity, "
            + "sample_count, payload, evidence_counts, since_version_id, escalated_at, triage_verdict, "
            + "triage_action, triage_summary, triage_citations, triaged_at, human_verdict_at, "
            + "recurrences_since_verdict, created_at, updated_at";

    /**
     * {@link FindingClaim}'s columns: what the detector asserted, and not one column any layer wrote
     * about it afterwards. The {@code triage_*} columns are absent on purpose — see {@link FindingClaim}.
     */
    private static final String CLAIM_COLS = "id, project_id, classifier_key, cause_key, subject_kind, "
            + "subject_id, subject_label, call_site_id, onset_at, last_seen_at, title, basis, severity, "
            + "sample_count, payload, evidence_counts, created_at";

    private static final String LIVE = "status IN ('open', 'blocked')";

    private final JdbcClient jdbc;

    public FindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The outcome of recording a firing against a cause.
     *
     * @param sampleCount the cause's RUNNING total after this firing — what the recurrence gate reads,
     *     so a cause seen once does not spend a microVM before it is worth one
     * @param escalatedAt when Layer-2 triage was enqueued, or null if it never was. Explicit
     *     rather than inferred from {@code created}: triage happens on the sweep where the cause
     *     crosses its recurrence bar, which is generally not the sweep that opened it
     */
    public record Recorded(
            String findingId,
            boolean created,
            long sampleCount,
            @Nullable String escalatedAt,
            /** Set once a human has ruled. Layer 2 must not re-litigate a settled cause. */
            @Nullable String humanVerdictAt,
            /**
             * What triage did to this finding, or null while nothing has ruled on it.
             *
             * <p>Read by the writers that keep a finding's evidence in step with its payload: a claim may
             * be re-pointed while no ruling stands, and never once one does. {@code reopenForTriage} nulls
             * this, so a finding sent back for a second look is re-pointable again, which is the property
             * that keeps the second look auditable.
             */
            @Nullable String triageAction) {

        /** Whether some ruling stands on this finding right now. */
        public boolean ruled() {
            return triageAction != null || humanVerdictAt != null;
        }
    }

    /**
     * Record {@code traceDelta} firings against a behaviour-drift cause, creating the OPEN finding if
     * this is the first.
     *
     * <p>The payload is first-write-wins, exactly as the exemplar used to be: it carries the native
     * cause vocabulary and the verdict the exemplar was judged under, and re-pointing that on every
     * later firing would make the evidence under an escalation unstable.
     */
    public Recorded recordFiring(
            String id,
            String projectId,
            String profileId,
            String causeKind,
            String causeKey,
            String workflowKey,
            long traceDelta,
            @Nullable String exemplarVerdictId,
            @Nullable String sinceVersionId,
            String callSiteId,
            String now) {
        String payload = payloadJson(nativeVocabulary(causeKind, workflowKey, causeKey, exemplarVerdictId));
        Recorded outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 subject_label, call_site_id, status, onset_at, last_seen_at,
                                 sample_count, payload, since_version_id, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :subjectLabel, :callSiteId,
                    'open', :now, :now, :delta, CAST(:payload AS jsonb), :versionId, :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status IN ('open', 'blocked') DO UPDATE SET
                sample_count = finding.sample_count + EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                -- Recurrences are firings that happened AFTER something ruled on the cause: a human
                -- pressing BLOCKED, or a triage run closing it. Both are a settled question that the
                -- traffic then contradicted, which is a much stronger claim than the ordinary
                -- accumulation an unruled finding does — and for the triage arm it is the counter the
                -- re-open rule reads.
                recurrences_since_verdict = finding.recurrences_since_verdict
                    + CASE WHEN finding.status = 'blocked' OR finding.triage_action = 'closed'
                           THEN EXCLUDED.sample_count ELSE 0 END
            RETURNING id, sample_count, escalated_at, human_verdict_at, triage_action
            """)
                .param("id", id)
                .param("pid", projectId)
                .param("classifier", "behavior_drift")
                .param("causeKey", CauseKey.behaviorDrift(profileId, causeKind, causeKey, workflowKey))
                .param("subjectKind", FindingRow.SubjectKind.BEHAVIOR_PROFILE)
                .param("subjectId", profileId)
                .param("subjectLabel", causeKey)
                .param("callSiteId", callSiteId)
                .param("delta", traceDelta)
                .param("payload", payload)
                .param("versionId", sinceVersionId)
                .param("now", now)
                .query((rs, n) -> recorded(rs))
                .single();
        return created(id, outcome);
    }

    /**
     * Record a closed window's shift against a metric-drift cause.
     *
     * <p><b>{@code sampleDelta} is a sample count, not a trace label.</b> Nothing here labelled a trace
     * — slow is not bad — and the column reads as "how much traffic this shift has been observed over".
     *
     * <p><b>The payload refreshes every window.</b> It says how far the bucket sits from its reference
     * NOW, and a twenty-window-old copy would be a wrong answer rather than a stale one: a human
     * pressing <em>Legitimate — absorb</em> is absorbing the current level.
     *
     * @param quietBefore the recovery horizon. A bucket that returned to its reference stops earning
     *     shifted windows, so its finding stops being bumped; a row unrefreshed past this instant had
     *     therefore recovered, and the window shifting now starts a NEW spell whose onset must move —
     *     which is what lets {@code CaseLedger.isNewSpell} reopen its case.
     */
    public Recorded recordShift(
            String id,
            String projectId,
            String classifierKey,
            String baselineId,
            String causeKey,
            long sampleDelta,
            @Nullable String sinceVersionId,
            String callSiteId,
            String evidenceJson,
            String quietBefore,
            String now) {
        String payload =
                mergeVocabulary(evidenceJson, nativeVocabulary("distribution_shift", "__global__", causeKey, null));
        Recorded outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 call_site_id, status, onset_at, last_seen_at, sample_count, payload,
                                 since_version_id, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :callSiteId,
                    'open', :now, :now, :delta, CAST(:payload AS jsonb), :versionId, :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status IN ('open', 'blocked') DO UPDATE SET
                sample_count = finding.sample_count + EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                payload = EXCLUDED.payload,
                -- The onset moves only across an observed recovery, never within a spell.
                onset_at = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN EXCLUDED.onset_at
                    ELSE finding.onset_at END,
                recurrences_since_verdict = finding.recurrences_since_verdict
                    + CASE WHEN finding.status = 'blocked' OR finding.triage_action = 'closed'
                           THEN EXCLUDED.sample_count ELSE 0 END
            RETURNING id, sample_count, escalated_at, human_verdict_at, triage_action
            """)
                .param("id", id)
                .param("pid", projectId)
                .param("classifier", classifierKey)
                .param("causeKey", CauseKey.metricDrift(baselineId, causeKey))
                .param("subjectKind", FindingRow.SubjectKind.METRIC_BASELINE)
                .param("subjectId", baselineId)
                .param("callSiteId", callSiteId)
                .param("delta", sampleDelta)
                .param("payload", payload)
                .param("versionId", sinceVersionId)
                .param("quietBefore", quietBefore)
                .param("now", now)
                .query((rs, n) -> recorded(rs))
                .single();
        return created(id, outcome);
    }

    /**
     * Record the current state of a recomputed cause — the tool-error path, and the only write here
     * that ASSIGNS its counts rather than accumulating them.
     *
     * <p>That difference is the whole of {@code classifiers/tool_error/PROGRAM.md} §5.1. There is no
     * sweep behind a rate_shift: the numbers are recomputed from an hourly aggregate on every read, so
     * an accumulating count would measure how often the recompute ran rather than how often the tool
     * failed. Run this a hundred times on unchanged traffic and the row is identical every time, which
     * is the property standing in for the cursor, watermark and transaction a sweep would have needed.
     *
     * <p><b>Observations refresh, judgements never do.</b> The update touches the counts, the clock and
     * the payload and the recurrence counter and nothing else — {@code triage_*}, {@code escalated_at},
     * {@code human_verdict_at} and {@code status} all survive.
     */
    public Recorded recordRecomputedCause(
            String id,
            String projectId,
            String causeKey,
            long observedCount,
            @Nullable String callSiteId,
            @Nullable String onsetAt,
            String evidenceJson,
            String quietBefore,
            String now) {
        String payload = mergeVocabulary(evidenceJson, nativeVocabulary("rate_shift", "", causeKey, null));
        Recorded outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 subject_label, call_site_id, status, onset_at, last_seen_at,
                                 sample_count, payload, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :subjectLabel, :callSiteId,
                    'open', :onsetAt, :now, :count, CAST(:payload AS jsonb), :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status IN ('open', 'blocked') DO UPDATE SET
                sample_count = EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                payload = EXCLUDED.payload,
                -- A row that went unrefreshed past :quietBefore had stopped firing, so this is a new
                -- spell rather than the same one still running. Text comparison because both sides are
                -- Instant.toString() — ISO-8601 UTC sorts lexicographically.
                onset_at = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN EXCLUDED.onset_at
                    ELSE finding.onset_at END,
                -- The recurrence counter, on a writer that ASSIGNS its count. Conditioned on the count
                -- having GROWN rather than on the write happening, because this statement runs on every
                -- recompute: counting calls would make the re-open rule fire on an unchanged tool at
                -- whatever rate the sweep happens to run, which is the idempotence this path is built on.
                recurrences_since_verdict = finding.recurrences_since_verdict
                    + CASE WHEN finding.triage_action = 'closed'
                                AND EXCLUDED.sample_count > finding.sample_count THEN 1 ELSE 0 END
            RETURNING id, sample_count, escalated_at, human_verdict_at, triage_action
            """)
                .param("id", id)
                .param("pid", projectId)
                .param("classifier", "tool_error")
                .param("causeKey", CauseKey.toolError(causeKey))
                .param("subjectKind", FindingRow.SubjectKind.TOOL)
                .param("subjectId", CauseKey.toolOf(causeKey))
                .param("subjectLabel", CauseKey.toolOf(causeKey))
                .param("callSiteId", callSiteId)
                .param("count", observedCount)
                .param("payload", payload)
                .param("onsetAt", onsetAt == null ? now : onsetAt)
                .param("quietBefore", quietBefore)
                .param("now", now)
                .query((rs, n) -> recorded(rs))
                .single();
        return created(id, outcome);
    }

    /**
     * Record a per-span classifier's armed window — {@code observedCount} detections inside the window
     * its owner configured — opening the finding if this is the first such window.
     *
     * <p><b>The count is ASSIGNED, not accumulated</b>, for the same reason
     * {@link #recordRecomputedCause} assigns: the number is what the current window holds, recomputed
     * from the detection table on every sweep, so adding it would measure how often the sweep ran. Run
     * this twice on an unchanged window and the row is identical both times, which is what makes the
     * sweep safe to re-run without a transaction spanning it.
     *
     * <p><b>The onset freezes within a spell.</b> A classifier that keeps breaching its bar keeps
     * refreshing one finding; only a window that went quiet past {@code quietBefore} starts a new spell
     * and moves the onset, which is what lets {@code CaseLedger.isNewSpell} reopen a case a human closed
     * rather than reopening it on the next tick.
     *
     * @param classifierId the classifier row's id — the subject AND (via {@link CauseKey#perSpanClassifier})
     *     the cause scope, so one classifier holds one live finding
     * @param label the classifier's display name, for a case title that reads as a sentence
     */
    public Recorded recordArmedWindow(
            String id,
            String projectId,
            String classifierKey,
            String classifierId,
            String label,
            long observedCount,
            @Nullable String callSiteId,
            String payloadJson,
            String quietBefore,
            String now) {
        Recorded outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 subject_label, call_site_id, status, onset_at, last_seen_at,
                                 sample_count, payload, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :subjectLabel, :callSiteId,
                    'open', :now, :now, :count, CAST(:payload AS jsonb), :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status IN ('open', 'blocked') DO UPDATE SET
                sample_count = EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                payload = EXCLUDED.payload,
                onset_at = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN EXCLUDED.onset_at
                    ELSE finding.onset_at END,
                -- Assigning writer, so the triage arm asks whether the window GREW — see
                -- recordRecomputedCause. The blocked arm keeps counting sweeps, unchanged.
                recurrences_since_verdict = finding.recurrences_since_verdict
                    + CASE WHEN finding.status = 'blocked' THEN 1
                           WHEN finding.triage_action = 'closed'
                                AND EXCLUDED.sample_count > finding.sample_count THEN 1
                           ELSE 0 END
            RETURNING id, sample_count, escalated_at, human_verdict_at, triage_action
            """)
                .param("id", id)
                .param("pid", projectId)
                .param("classifier", classifierKey)
                .param("causeKey", CauseKey.perSpanClassifier(classifierId))
                .param("subjectKind", FindingRow.SubjectKind.CLASSIFIER)
                .param("subjectId", classifierId)
                .param("subjectLabel", label)
                .param("callSiteId", callSiteId)
                .param("count", observedCount)
                .param("payload", payloadJson)
                .param("quietBefore", quietBefore)
                .param("now", now)
                .query((rs, n) -> recorded(rs))
                .single();
        return created(id, outcome);
    }

    /**
     * Stamp the finding as triaged-once. Conditional on {@code escalated_at IS NULL} so two sweeps
     * racing the same cause enqueue exactly one microVM between them; the loser sees 0 rows and skips.
     */
    public boolean markEscalated(String projectId, String findingId, String now) {
        return jdbc.sql("UPDATE finding SET escalated_at = :now, updated_at = :now"
                                + " WHERE project_id = :pid AND id = :id AND escalated_at IS NULL")
                        .param("now", now)
                        .param("pid", projectId)
                        .param("id", findingId)
                        .update()
                == 1;
    }

    /**
     * Record a triage ruling on the finding, and the action it fixes.
     *
     * <p><b>The action is derived here rather than passed in</b>, so the mapping
     * ({@link FindingRow.TriageAction#of}) has exactly one implementation and no caller can record a
     * {@code positive} that closed. Both land in the same statement, which
     * {@code finding_triage_paired_check} then enforces — a verdict observed without its action would
     * read as a ruling nothing acted on.
     *
     * <p><b>The recurrence counter resets.</b> {@code recurrences_since_verdict} is "firings since the
     * question was settled", so a ruling starts it at zero; what it counts from here is what the re-open
     * rule reads. It does NOT touch {@code status}: closing is {@code triage_action}, and the row stays
     * in the live index precisely so its cause can keep firing against it.
     */
    public int recordTriage(
            String projectId,
            String findingId,
            String verdict,
            String summary,
            @Nullable String citationsJson,
            String now) {
        return jdbc.sql("""
            UPDATE finding
               SET triage_verdict = :verdict,
                   triage_action = :action,
                   triage_summary = :summary,
                   triage_citations = CAST(:citations AS jsonb),
                   triaged_at = :now,
                   recurrences_since_verdict = 0,
                   updated_at = :now
             WHERE project_id = :pid AND id = :id
            """)
                .param("verdict", verdict)
                .param("action", FindingRow.TriageAction.of(verdict))
                .param("summary", summary)
                .param("citations", citationsJson)
                .param("now", now)
                .param("pid", projectId)
                .param("id", findingId)
                .update();
    }

    /**
     * Send a closed finding back through triage, because its cause kept firing.
     *
     * <p>Clears the ruling and the escalation stamp together: {@code escalated_at} is the once-per-cause
     * gate every enqueue path checks, so a re-open that left it set would be a finding nobody could
     * schedule. The counter goes back to zero because it is about to start counting firings since THIS
     * decision rather than the last one.
     *
     * <p>Conditional on the finding actually being closed, which is what makes it race-safe: two
     * schedulers reaching the threshold in the same tick produce one re-open and one no-op, and the
     * loser sees 0 rows.
     */
    public int reopenForTriage(String projectId, String findingId, String now) {
        return jdbc.sql("""
            UPDATE finding
               SET triage_verdict = NULL,
                   triage_action = NULL,
                   triage_summary = NULL,
                   triage_citations = NULL,
                   triaged_at = NULL,
                   escalated_at = NULL,
                   recurrences_since_verdict = 0,
                   updated_at = :now
             WHERE project_id = :pid AND id = :id AND triage_action = :closed
            """)
                .param("now", now)
                .param("pid", projectId)
                .param("id", findingId)
                .param("closed", FindingRow.TriageAction.CLOSED)
                .update();
    }

    /**
     * The closed findings whose cause has fired at least {@code minRecurrences} times since the ruling
     * and is still firing within the window — what the re-open rule is about to act on, worst-first.
     *
     * <p>Liveness is {@code last_seen_at}, not a status: a cause that recovered simply stops being
     * bumped, so a finding that recurred three times a month ago and nothing since is exactly what the
     * window is there to leave alone.
     */
    public List<FindingRow> listRecurringClosed(String projectId, long minRecurrences, String seenSince, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM finding"
                        + " WHERE project_id = :pid AND " + LIVE
                        + "   AND triage_action = :closed"
                        + "   AND human_verdict_at IS NULL"
                        + "   AND recurrences_since_verdict >= :minRecurrences"
                        + "   AND last_seen_at >= :since"
                        + " ORDER BY recurrences_since_verdict DESC, last_seen_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("closed", FindingRow.TriageAction.CLOSED)
                .param("minRecurrences", minRecurrences)
                .param("since", seenSince)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Findings for a project, optionally narrowed to one call-site scope and one classifier.
     *
     * <p>Both filters are applied in SQL rather than in the client, and the row limit is why. Behaviour
     * drift is scoped per call site, so a page rendering one section per scope was slicing a single
     * project-wide {@code LIMIT} across all of them — a busy scope could exhaust it and a quiet one
     * would render "no findings" while findings existed. The classifier rail has the same problem in
     * the other axis.
     */
    public List<FindingRow> listByProject(
            String projectId,
            @Nullable String status,
            @Nullable String callSiteId,
            @Nullable String classifierKey,
            boolean confirmedOnly,
            int limit) {
        // `open` means the LIVE set, not literally status='open'. A cause a human blocked that keeps
        // firing is the most live thing here, and a literal filter made the gate's blocked arm
        // unsatisfiable (status='open' AND status='blocked') — the recurrence feature was unreachable
        // in the product while passing at the repository.
        String statusClause = status == null
                ? ""
                : (FindingRow.Status.OPEN.equals(status)
                        ? " AND (status = :status OR (status = '" + FindingRow.Status.BLOCKED
                                + "' AND recurrences_since_verdict > 0))"
                        : " AND status = :status");
        String scopeClause = callSiteId == null ? "" : " AND call_site_id = :callSiteId";
        String classifierClause = classifierKey == null ? "" : " AND classifier_key = :classifier";
        // The Layer-2 gate, applied in SQL so the row limit bounds what is SHOWN. A human ruling
        // outranks the machine's: a cause a human called a deviation, that then recurred, is the most
        // confirmed thing here — and it carries no triage verdict precisely because the sweep
        // skips Layer 2 once a human has ruled.
        String gateClause = confirmedOnly
                ? " AND (triage_verdict = '" + FindingRow.TriageVerdict.POSITIVE + "'" + " OR (status = '"
                        + FindingRow.Status.BLOCKED + "' AND recurrences_since_verdict > 0))"
                : "";
        var spec = jdbc.sql("SELECT " + COLS + " FROM finding WHERE project_id = :pid" + statusClause + scopeClause
                        + classifierClause + gateClause + " ORDER BY last_seen_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit);
        if (status != null) spec = spec.param("status", status);
        if (callSiteId != null) spec = spec.param("callSiteId", callSiteId);
        if (classifierKey != null) spec = spec.param("classifier", classifierKey);
        return spec.query((rs, n) -> map(rs)).list();
    }

    /**
     * How many findings are live — the number a case surface offers as "review N findings".
     *
     * <p>{@link #LIVE}, not {@code status = 'open'}, and the distinction is the whole point of having
     * the constant: the findings API's {@code ?status=open} resolves to this same set (see the status
     * clause in {@link #list}), so a count built from a fresh literal here would send a reader to a list
     * holding a different number of rows than the button that brought them there promised.
     */
    public long countLive(String projectId) {
        return jdbc.sql("SELECT count(*) FROM finding WHERE project_id = :pid AND " + LIVE)
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /**
     * How many live findings the Layer-2 gate is holding back — everything not yet triaged, plus
     * everything the repo said was legitimate or could not settle.
     *
     * <p>Counted rather than inferred from the list, because the list is capped: "showing 3 of 200" has
     * to be true even when the withheld set is larger than any page of it.
     */
    public long countWithheld(String projectId, @Nullable String callSiteId) {
        String scopeClause = callSiteId == null ? "" : " AND call_site_id = :callSiteId";
        var spec = jdbc.sql("SELECT count(*) FROM finding WHERE project_id = :pid AND " + LIVE
                        + " AND (triage_verdict IS NULL OR triage_verdict <> '"
                        + FindingRow.TriageVerdict.POSITIVE + "')" + scopeClause)
                .param("pid", projectId);
        if (callSiteId != null) spec = spec.param("callSiteId", callSiteId);
        return spec.query(Long.class).single();
    }

    /**
     * The survived-analysis predicate, PARAMETERIZED per classifier gate rather than unified.
     *
     * <p>Three real variants exist and collapsing them would silently change what a case means for two
     * of the three detectors. {@link #MACHINE_OR_HUMAN} is metric drift and tool error: Layer 2 ruled it
     * a deviation, or a human pressed <em>Real deviation</em> (which sets BLOCKED and zeroes the
     * recurrence counter, so gating the human arm on recurrences would hide a just-confirmed regression
     * until its bucket shifted again). {@link #MACHINE_ONLY} is conformance, where a human verb RESOLVES
     * the row instead of marking it, so there is no blocked arm to read. {@link #HUMAN_RECURRENCE} is
     * behaviour drift's findings-page gate, which requires the cause to have recurred SINCE the ruling.
     */
    public enum SurvivalGate {
        MACHINE_OR_HUMAN("(triage_verdict = '" + FindingRow.TriageVerdict.POSITIVE + "' OR status = '"
                + FindingRow.Status.BLOCKED + "')"),
        MACHINE_ONLY("triage_verdict = '" + FindingRow.TriageVerdict.POSITIVE + "'"),
        HUMAN_RECURRENCE("(status = '" + FindingRow.Status.BLOCKED + "' AND recurrences_since_verdict > 0)");

        private final String sql;

        SurvivalGate(String sql) {
            this.sql = sql;
        }

        /**
         * The gate as SQL, for the two readers that cannot call {@link #listSurvivingAnalysis} — the
         * confirmed-span exclusion, and the conformance projection, which selects its statistics out of
         * {@code payload} and so builds its own SELECT list. They still take the predicate from here:
         * a case source and the query that decides what a case MEANS must not be able to disagree.
         */
        public String sql() {
            return sql;
        }
    }

    /**
     * The findings of one or more classifiers that have earned a case: live, past the gate, and still
     * firing within the caller's quiet window.
     *
     * <p><b>Both halves of the gate are in SQL, deliberately.</b> A case source hands
     * {@code CaseReconciler} the full live set every pass and the reconciler closes whatever dropped
     * out, so a row filtered in Java after a {@code LIMIT} would not merely be hidden — it would read as
     * a recovery and close a case that is still firing.
     *
     * <p><b>Liveness is recency, not status.</b> Nothing writes "this came back": a recovered population
     * simply stops earning firings, so its finding stops being bumped and {@code last_seen_at} stops
     * advancing.
     */
    public List<FindingRow> listSurvivingAnalysis(
            String projectId, Collection<String> classifierKeys, SurvivalGate gate, String seenSince, int limit) {
        if (classifierKeys.isEmpty()) return List.of();
        return jdbc.sql("SELECT " + COLS + " FROM finding"
                        + " WHERE project_id = :pid AND classifier_key IN (:classifiers) AND " + LIVE
                        + "   AND " + gate.sql()
                        + "   AND last_seen_at >= :since"
                        + " ORDER BY last_seen_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("classifiers", classifierKeys)
                .param("since", seenSince)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The findings automatic mode may escalate, worst-first — open, never escalated, never ruled on by
     * a human, carrying an exemplar, and observed over at least {@code minSampleCount} samples.
     *
     * <p>Every clause is a refusal to spend a ruling on something that cannot use one.
     * {@code escalated_at IS NULL} makes this once-per-cause, and it is set by the same
     * {@link #markEscalated} a hand-press uses, so the two triggers cannot double-spend.
     * {@code human_verdict_at IS NULL} keeps Layer 2 from re-litigating a cause a person has settled.
     * The evidence EXISTS clause is a liveness check, not an entry point: a finding whose cited traces
     * have all aged out is one Layer 2 cannot read, and this is where that shows up rather than inside
     * the sandbox. It is deliberately role-agnostic — naming one trace as the way in biases the run
     * that reads it, and detectors that write no {@code exemplar} are still fully readable.
     *
     * <p>Ordered by sample count descending: if the budget allows five rulings, they should be spent on
     * the five causes that have happened most, not the five inserted first.
     */
    public List<FindingRow> listAutoEscalatable(
            String projectId, Collection<String> classifierKeys, long minSampleCount, int limit) {
        if (classifierKeys.isEmpty()) return List.of();
        return jdbc.sql("SELECT " + COLS + " FROM finding f"
                        + " WHERE f.project_id = :pid AND f.classifier_key IN (:classifiers)"
                        + "   AND f.status = '" + FindingRow.Status.OPEN + "'"
                        + "   AND f.escalated_at IS NULL"
                        + "   AND f.human_verdict_at IS NULL"
                        + "   AND f.triage_verdict IS NULL"
                        + "   AND f.sample_count >= :minCount"
                        + "   AND EXISTS (SELECT 1 FROM finding_evidence e"
                        + "                WHERE e.finding_id = f.id AND e.trace_id IS NOT NULL)"
                        + " ORDER BY f.sample_count DESC, f.onset_at ASC LIMIT :limit")
                .param("pid", projectId)
                .param("classifiers", classifierKeys)
                .param("minCount", minSampleCount)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * The spells a currently-confirmed regression has run through, per subject — {@code [onset_at,
     * last_seen_at]} for every finding of these classifiers that survived the same gate a case needs.
     *
     * <p>This is what keeps a confirmed regression OUT of the rolling control it would otherwise become.
     * The traffic of a day a confirmed deviation ran through is not that bucket's normal, and folding it
     * in would let the very shift under investigation quietly become the bar the next window is judged
     * against.
     *
     * <p>One query per pass rather than one per baseline: a project sweeps thousands of buckets and the
     * confirmed set is small, so this is a map built once and consulted in memory.
     */
    public Map<String, List<ConfirmedSpan>> confirmedSpansBySubject(
            String projectId, Collection<String> classifierKeys) {
        if (classifierKeys.isEmpty()) return Map.of();
        record Row(String subjectId, ConfirmedSpan span) {}
        List<Row> rows = jdbc.sql("SELECT subject_id, onset_at, last_seen_at FROM finding"
                        + " WHERE project_id = :pid AND classifier_key IN (:classifiers) AND " + LIVE
                        // The same gate listSurvivingAnalysis applies, and deliberately the same one: a
                        // finding too unconfirmed to open a case is too unconfirmed to disqualify a day's
                        // traffic from being normal. Layer 1 detects change and cannot tell change from a
                        // problem, so excluding on an untriaged finding would blind the control to
                        // every legitimate shift the product ever makes.
                        + "   AND " + SurvivalGate.MACHINE_OR_HUMAN.sql())
                .param("pid", projectId)
                .param("classifiers", classifierKeys)
                .query((rs, n) -> new Row(
                        rs.getString("subject_id"),
                        new ConfirmedSpan(rs.getString("onset_at"), rs.getString("last_seen_at"))))
                .list();
        Map<String, List<ConfirmedSpan>> out = new LinkedHashMap<>();
        for (Row row : rows) {
            out.computeIfAbsent(row.subjectId(), k -> new ArrayList<>()).add(row.span());
        }
        return out;
    }

    /**
     * One confirmed spell's inclusive bounds, as the ISO strings the columns hold. Left as text because
     * every consumer compares them against other ISO text — the day keys of a control ring — and parsing
     * to {@code Instant} only to format back would be a round trip with a timezone in the middle of it.
     */
    public record ConfirmedSpan(String fromAt, String toAt) {}

    public Optional<FindingRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM finding WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * One finding's CLAIM — the same row, read through a SELECT list that cannot see what any layer
     * ruled about it. This is RCA's only door onto a finding, and the reason it is a second method
     * rather than a filter on {@link #findById} is that a filter is a convention while a column list is
     * a fact: no {@code triage_*} value is ever in scope for the caller to leak into a dossier.
     */
    public Optional<FindingClaim> findClaim(String projectId, String id) {
        return jdbc.sql("SELECT " + CLAIM_COLS + " FROM finding WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> mapClaim(rs))
                .optional();
    }

    /**
     * Move a finding out of {@code open} — a HUMAN resolution only. It stamps {@code human_verdict_at},
     * which permanently suppresses Layer 2 for the cause, so graduation must not route through here.
     */
    public int setStatus(String projectId, String id, String status, String now) {
        return jdbc.sql("UPDATE finding SET status = :status, "
                        // Timestamp and counter reset TOGETHER. COALESCE-ing the timestamp while zeroing
                        // the count let a re-ruled finding report "you marked this on <first verdict> — it
                        // has happened N times since", where N counted only from the SECOND verdict.
                        + "human_verdict_at = :now, recurrences_since_verdict = 0, updated_at = :now "
                        + "WHERE project_id = :pid AND id = :id")
                .param("now", now)
                .param("status", status)
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /**
     * Close a finding without recording a human ruling — the conformance resolve and the graduation
     * path. Deliberately separate from {@link #setStatus}: stamping {@code human_verdict_at} here would
     * tell Layer 2 a person had settled a cause nobody looked at.
     */
    public int resolve(String projectId, String id, String status, String now) {
        return jdbc.sql("UPDATE finding SET status = :status, updated_at = :now"
                        + " WHERE project_id = :pid AND id = :id")
                .param("status", status)
                .param("now", now)
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /**
     * Close every open behaviour-drift finding whose cause is the graduated gram — its alerts must stop
     * by themselves. Matched on the profile subject plus the classifier's own key inside the payload,
     * because the scoped {@code cause_key} folds the cause kind in and a graduation is about the gram
     * whichever kind fired on it.
     */
    public int resolveForNativeCause(String projectId, String profileId, String nativeCauseKey, String status) {
        return jdbc.sql("UPDATE finding SET status = :status, updated_at = :now"
                        + " WHERE project_id = :pid AND subject_kind = :subjectKind AND subject_id = :profileId"
                        + "   AND payload ->> 'native_cause_key' = :cause AND status = 'open'")
                .param("status", status)
                .param("now", java.time.Instant.now().toString())
                .param("pid", projectId)
                .param("subjectKind", FindingRow.SubjectKind.BEHAVIOR_PROFILE)
                .param("profileId", profileId)
                .param("cause", nativeCauseKey)
                .update();
    }

    private static Recorded recorded(ResultSet rs) throws SQLException {
        return new Recorded(
                rs.getString("id"),
                false,
                rs.getLong("sample_count"),
                rs.getString("escalated_at"),
                rs.getString("human_verdict_at"),
                rs.getString("triage_action"));
    }

    private static Recorded created(String id, Recorded outcome) {
        return new Recorded(
                outcome.findingId(),
                id.equals(outcome.findingId()),
                outcome.sampleCount(),
                outcome.escalatedAt(),
                outcome.humanVerdictAt(),
                outcome.triageAction());
    }

    /**
     * The classifier-native vocabulary the scoped {@code cause_key} folds in, so nothing that was
     * recorded stops being readable. Built as a literal rather than through Jackson because the values
     * are ids and enum words, and a JSON writer here would be a dependency for four string members.
     */
    private static Map<String, String> nativeVocabulary(
            String causeKind, String workflowKey, String nativeCauseKey, @Nullable String exemplarVerdictId) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("cause_kind", causeKind);
        out.put("workflow_key", workflowKey);
        out.put("native_cause_key", nativeCauseKey);
        if (exemplarVerdictId != null) out.put("exemplar_verdict_id", exemplarVerdictId);
        return out;
    }

    private static String payloadJson(Map<String, String> members) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : members.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * The classifier's own evidence blob with the native vocabulary merged in at the top level. Done in
     * SQL-free string terms only when the blob is absent; otherwise the caller's JSON is spliced, which
     * keeps the detector's document byte-identical to what it wrote.
     */
    private static String mergeVocabulary(@Nullable String evidenceJson, Map<String, String> vocabulary) {
        String vocab = payloadJson(vocabulary);
        if (evidenceJson == null || evidenceJson.isBlank()) return vocab;
        String trimmed = evidenceJson.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return vocab;
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) return vocab;
        return "{" + body + "," + vocab.substring(1);
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static FindingRow map(ResultSet rs) throws SQLException {
        return new FindingRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("classifier_key"),
                rs.getString("cause_key"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("subject_label"),
                rs.getString("call_site_id"),
                rs.getString("status"),
                rs.getString("onset_at"),
                rs.getString("last_seen_at"),
                rs.getString("title"),
                rs.getString("basis"),
                (Double) rs.getObject("severity"),
                rs.getLong("sample_count"),
                rs.getString("payload"),
                rs.getString("evidence_counts"),
                rs.getString("since_version_id"),
                rs.getString("escalated_at"),
                rs.getString("triage_verdict"),
                rs.getString("triage_action"),
                rs.getString("triage_summary"),
                rs.getString("triage_citations"),
                rs.getString("triaged_at"),
                rs.getString("human_verdict_at"),
                rs.getLong("recurrences_since_verdict"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static FindingClaim mapClaim(ResultSet rs) throws SQLException {
        return new FindingClaim(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("classifier_key"),
                rs.getString("cause_key"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("subject_label"),
                rs.getString("call_site_id"),
                rs.getString("onset_at"),
                rs.getString("last_seen_at"),
                rs.getString("title"),
                rs.getString("basis"),
                (Double) rs.getObject("severity"),
                rs.getLong("sample_count"),
                rs.getString("payload"),
                rs.getString("evidence_counts"),
                rs.getString("created_at"));
    }
}
