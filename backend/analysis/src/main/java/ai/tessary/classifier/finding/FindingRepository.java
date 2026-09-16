// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

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
 * {@code (project_id, classifier_key, cause_key)} with the predicate {@code status = 'open' AND
 * triage_verdict IS NULL}. That is the whole point of composing scope into {@link CauseKey}: the three
 * writers used to conflict on three different partial indexes, and Postgres infers the arbiter from the
 * conflict target, so one method could not name them all. The predicate is restated character for
 * character in each statement because a partial index is matched by its expression.
 *
 * <p><b>A ruled finding leaves the index by construction.</b> Once a verdict lands — machine or human —
 * the row no longer satisfies {@code triage_verdict IS NULL}, so no upsert here can ever conflict onto
 * it again: the next firing of the same cause INSERTs a fresh open row instead of silently mutating a
 * settled one. That is also why the payload-freeze and recurrence-counter branches every upsert used to
 * carry are gone — there is no ruled row left for them to guard against.
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
            + "case_id, created_at, updated_at";

    /**
     * {@link FindingClaim}'s columns: what the detector asserted, and not one column any layer wrote
     * about it afterwards. The {@code triage_*} columns are absent on purpose — see {@link FindingClaim}.
     */
    private static final String CLAIM_COLS = "id, project_id, classifier_key, cause_key, subject_kind, "
            + "subject_id, subject_label, call_site_id, onset_at, last_seen_at, title, basis, severity, "
            + "sample_count, payload, evidence_counts, created_at";

    /** Every finding, ruled or not, still short of a settled negative. */
    private static final String LIVE = "status = 'open'";

    /** The arbiter every upsert conflicts on — see the class javadoc. Restated literally in each
     *  statement, since a partial index is matched by its expression rather than by name. */
    private static final String OPEN_UNRULED = "status = 'open' AND triage_verdict IS NULL";

    private final JdbcClient jdbc;

    public FindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every finding on this install, in any status and any project: the telemetry heartbeat's
     *  {@code counts.findings} (devdocs/reference/telemetry-contract.md §1). */
    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM finding").query(Long.class).single();
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
            @Nullable String escalatedAt) {}

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
                WHERE status = 'open' AND triage_verdict IS NULL DO UPDATE SET
                sample_count = finding.sample_count + EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at
            RETURNING id, sample_count, escalated_at
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
     * @param eventAt the window's own EVENT time — the closing sample's, not the sweep's wall clock
     *     [R11]. Written into both {@code onset_at} (on first insert) and {@code last_seen_at} (on every
     *     write), so a confirmed regression is excluded by the event span it actually ran through, and a
     *     replayed backfill's spells read on the same clock its windows themselves are cut on.
     * @param quietBefore the recovery horizon, itself measured back from {@code eventAt}. A bucket that
     *     returned to its reference stops earning shifted windows, so its finding stops being bumped; a
     *     row unrefreshed past this instant had therefore recovered, and the window shifting now starts a
     *     NEW spell whose onset must move — which is what lets {@code CaseLedger.isNewSpell} reopen its
     *     case.
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
            String eventAt,
            String quietBefore,
            String now) {
        String payload =
                mergeVocabulary(evidenceJson, nativeVocabulary("distribution_shift", "__global__", causeKey, null));
        Recorded outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 call_site_id, status, onset_at, last_seen_at, sample_count, payload,
                                 since_version_id, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :callSiteId,
                    'open', :eventAt, :eventAt, :delta, CAST(:payload AS jsonb), :versionId, :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status = 'open' AND triage_verdict IS NULL DO UPDATE SET
                sample_count = finding.sample_count + EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                -- The conflict target only ever matches an unruled row (see the class javadoc), so the
                -- payload here is always safe to re-point: nothing has read and ruled on this claim yet.
                payload = EXCLUDED.payload,
                -- The onset moves only across an observed recovery, never within a spell. Both sides are
                -- now EVENT time.
                onset_at = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN EXCLUDED.onset_at
                    ELSE finding.onset_at END
            RETURNING id, sample_count, escalated_at
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
                .param("eventAt", eventAt)
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
        return recordRecomputedRate(
                id,
                projectId,
                "tool_error",
                CauseKey.toolError(causeKey),
                FindingRow.Cause.RATE_SHIFT,
                causeKey,
                FindingRow.SubjectKind.TOOL,
                CauseKey.toolOf(causeKey),
                CauseKey.toolOf(causeKey),
                observedCount,
                callSiteId,
                onsetAt,
                evidenceJson,
                quietBefore,
                now);
    }

    /**
     * {@link #recordRecomputedCause}'s write for any classifier that recomputes a rate from an hourly
     * aggregate on every pass. Malformed Output is the second: it runs tool_error's engine over call sites,
     * and so needs exactly this write's contract of assigned counts, refreshed observations and untouched
     * judgements, under its own classifier, cause kind and subject.
     *
     * @param nativeCauseKey the classifier's own name for the cause, recorded in the payload vocabulary
     */
    public Recorded recordRecomputedRate(
            String id,
            String projectId,
            String classifierKey,
            String causeKey,
            String causeKind,
            String nativeCauseKey,
            String subjectKind,
            String subjectId,
            String subjectLabel,
            long observedCount,
            @Nullable String callSiteId,
            @Nullable String onsetAt,
            String evidenceJson,
            String quietBefore,
            String now) {
        String payload = mergeVocabulary(evidenceJson, nativeVocabulary(causeKind, "", nativeCauseKey, null));
        Recorded outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 subject_label, call_site_id, status, onset_at, last_seen_at,
                                 sample_count, payload, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :subjectLabel, :callSiteId,
                    'open', :onsetAt, :now, :count, CAST(:payload AS jsonb), :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status = 'open' AND triage_verdict IS NULL DO UPDATE SET
                sample_count = EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                -- The conflict target only ever matches an unruled row — see recordShift.
                payload = EXCLUDED.payload,
                -- A row that went unrefreshed past :quietBefore had stopped firing, so this is a new
                -- spell rather than the same one still running. Text comparison because both sides are
                -- Instant.toString() — ISO-8601 UTC sorts lexicographically.
                onset_at = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN EXCLUDED.onset_at
                    ELSE finding.onset_at END
            RETURNING id, sample_count, escalated_at
            """)
                .param("id", id)
                .param("pid", projectId)
                .param("classifier", classifierKey)
                .param("causeKey", causeKey)
                .param("subjectKind", subjectKind)
                .param("subjectId", subjectId)
                .param("subjectLabel", subjectLabel)
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
                WHERE status = 'open' AND triage_verdict IS NULL DO UPDATE SET
                sample_count = EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                -- The conflict target only ever matches an unruled row — see recordShift.
                payload = EXCLUDED.payload,
                onset_at = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN EXCLUDED.onset_at
                    ELSE finding.onset_at END
            RETURNING id, sample_count, escalated_at
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
     * Open or refresh the finding for one facet of a per-span classifier (one call site, one kind of thing
     * the detector saw) from an event-time window that crossed the classifier's bar.
     *
     * <p><b>Every field moves forward only.</b> Windows are bucketed on when the span happened, and a late
     * upload can report a window older than the one this finding already holds. So {@code last_seen_at}
     * keeps the later of the two, {@code sample_count} and {@code payload} belong to the newest window and
     * an older one leaves them alone, and {@code onset_at} starts a new spell only when a NEWER window
     * follows a quiet gap, never because an old window arrived late.
     *
     * <p>The comparisons cast to {@code timestamptz}. The columns are text, and {@code Instant#toString}
     * drops a zero fraction, so {@code ...:00.5Z} sorts before {@code ...:00Z} as a string while being
     * later as an instant.
     *
     * @param onsetAt the window's start
     * @param lastSeenAt when the latest detection in the window happened
     * @param quietBefore a finding last seen before this has been quiet long enough that a newer window
     *     is a new spell
     */
    public Recorded recordArmedFacet(
            String id,
            String projectId,
            String classifierKey,
            String classifierId,
            String label,
            @Nullable String callSiteId,
            String facet,
            long observedCount,
            String onsetAt,
            String lastSeenAt,
            String payloadJson,
            String quietBefore,
            String now) {
        Recorded outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 subject_label, call_site_id, status, onset_at, last_seen_at,
                                 sample_count, payload, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :subjectLabel, :callSiteId,
                    'open', :onsetAt, :lastSeenAt, :count, CAST(:payload AS jsonb), :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status = 'open' AND triage_verdict IS NULL DO UPDATE SET
                updated_at = EXCLUDED.updated_at,
                onset_at = CASE
                    WHEN CAST(EXCLUDED.last_seen_at AS timestamptz) > CAST(finding.last_seen_at AS timestamptz)
                         AND CAST(finding.last_seen_at AS timestamptz) < CAST(:quietBefore AS timestamptz)
                    THEN EXCLUDED.onset_at ELSE finding.onset_at END,
                sample_count = CASE
                    WHEN CAST(EXCLUDED.last_seen_at AS timestamptz) >= CAST(finding.last_seen_at AS timestamptz)
                    THEN EXCLUDED.sample_count ELSE finding.sample_count END,
                -- The conflict target only ever matches an unruled row — see recordShift — so the
                -- newer-window replace and the sticky-confidence merge below need no freeze guard.
                payload = (CASE
                        WHEN CAST(EXCLUDED.last_seen_at AS timestamptz) >= CAST(finding.last_seen_at AS timestamptz)
                        THEN EXCLUDED.payload ELSE finding.payload END
                        -- High confidence is sticky across windows, in either arrival order: one high-band
                        -- detection is enough to call the whole facet a real credential.
                        || CASE WHEN finding.payload ->> 'confidence' = 'high' OR EXCLUDED.payload ->> 'confidence' = 'high'
                                THEN '{"confidence": "high"}'::jsonb ELSE '{}'::jsonb END),
                last_seen_at = CASE
                    WHEN CAST(EXCLUDED.last_seen_at AS timestamptz) > CAST(finding.last_seen_at AS timestamptz)
                    THEN EXCLUDED.last_seen_at ELSE finding.last_seen_at END
            RETURNING id, sample_count, escalated_at
            """)
                .param("id", id)
                .param("pid", projectId)
                .param("classifier", classifierKey)
                .param("causeKey", CauseKey.perSpanClassifierFacet(classifierId, callSiteId, facet))
                .param("subjectKind", FindingRow.SubjectKind.CLASSIFIER)
                .param("subjectId", classifierId)
                .param("subjectLabel", label)
                .param("callSiteId", callSiteId)
                .param("count", observedCount)
                .param("onsetAt", onsetAt)
                .param("lastSeenAt", lastSeenAt)
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
     * Record Layer 2's ruling on the finding, and the action and status it fixes.
     *
     * <p><b>The action and status are derived here rather than passed in</b>, so the mapping
     * ({@link FindingRow.TriageAction#of}) has exactly one implementation and no caller can record a
     * {@code positive} that closed. Verdict, action and status land in the same statement, which
     * {@code finding_triage_paired_check} then enforces — a verdict observed without its action would
     * read as a ruling nothing acted on.
     *
     * <p><b>The {@link #OPEN_UNRULED} guard is the whole of "a ruling freezes the finding".</b> Zero
     * rows updated means something else ruled first — another triage run, or a person pressing a verb —
     * and the caller (see {@code BehaviorTriageWorker}) logs that as a lost race rather than an error:
     * the cost was spent, but the answer it produced is moot.
     */
    public int recordTriage(
            String projectId,
            String findingId,
            String verdict,
            String summary,
            @Nullable String citationsJson,
            String now) {
        return jdbc.sql("UPDATE finding"
                        + "    SET triage_verdict = :verdict,"
                        + "        triage_action = :action,"
                        + "        triage_summary = :summary,"
                        + "        triage_citations = CAST(:citations AS jsonb),"
                        + "        triaged_at = :now,"
                        + "        status = CASE WHEN :verdict = 'negative' THEN 'closed' ELSE 'open' END,"
                        + "        updated_at = :now"
                        + "  WHERE project_id = :pid AND id = :id AND " + OPEN_UNRULED)
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
     * Record a PERSON's ruling on the finding — the same write {@link #recordTriage} does, plus
     * {@code human_verdict_at}. Guarded by the same {@link #OPEN_UNRULED} predicate: a verb pressed on
     * an already-ruled finding updates zero rows, which is what the correction loop turns into a
     * {@code 409 FINDING_CLOSED} rather than silently overwriting whichever ruling landed first.
     */
    public int recordHumanRuling(String projectId, String findingId, String verdict, String summary, String now) {
        return jdbc.sql("UPDATE finding"
                        + "    SET triage_verdict = :verdict,"
                        + "        triage_action = :action,"
                        + "        triage_summary = :summary,"
                        + "        triaged_at = :now,"
                        + "        human_verdict_at = :now,"
                        + "        status = CASE WHEN :verdict = 'negative' THEN 'closed' ELSE 'open' END,"
                        + "        updated_at = :now"
                        + "  WHERE project_id = :pid AND id = :id AND " + OPEN_UNRULED)
                .param("verdict", verdict)
                .param("action", FindingRow.TriageAction.of(verdict))
                .param("summary", summary)
                .param("now", now)
                .param("pid", projectId)
                .param("id", findingId)
                .update();
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
        String statusClause = status == null ? "" : " AND status = :status";
        String scopeClause = callSiteId == null ? "" : " AND call_site_id = :callSiteId";
        String classifierClause = classifierKey == null ? "" : " AND classifier_key = :classifier";
        // The Layer-2 gate, applied in SQL so the row limit bounds what is SHOWN. One clause now
        // covers every source of a ruling — triage, a person's verb, a high-confidence secret leak
        // written at arming — because all three write the same triage_verdict column.
        String gateClause = confirmedOnly ? " AND triage_verdict = '" + FindingRow.TriageVerdict.POSITIVE + "'" : "";
        var spec = jdbc.sql("SELECT " + COLS + " FROM finding WHERE project_id = :pid" + statusClause + scopeClause
                        + classifierClause + gateClause + " ORDER BY last_seen_at DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit);
        if (status != null) spec = spec.param("status", status);
        if (callSiteId != null) spec = spec.param("callSiteId", callSiteId);
        if (classifierKey != null) spec = spec.param("classifier", classifierKey);
        return spec.query((rs, n) -> map(rs)).list();
    }

    /** How many findings are open — the number a case surface offers as "review N findings". */
    public long countOpen(String projectId) {
        return jdbc.sql("SELECT count(*) FROM finding WHERE project_id = :pid AND " + LIVE)
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /** Every finding linked to one case, newest first — a case's own findings, per its {@code case_id}. */
    public List<FindingRow> listByCase(String projectId, String caseId) {
        return jdbc.sql("SELECT " + COLS + " FROM finding WHERE project_id = :pid AND case_id = :caseId"
                        + " ORDER BY created_at DESC")
                .param("pid", projectId)
                .param("caseId", caseId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Link a finding to the case its positive ruling opened or joined. Guarded on
     * {@code case_id IS NULL}: a finding is linked once, by the first case to claim it, so a case that
     * lost a race to open on the same key does not steal a finding another case already holds.
     */
    public boolean attachToCase(String projectId, String findingId, String caseId, String now) {
        return jdbc.sql("UPDATE finding SET case_id = :caseId, updated_at = :now"
                                + " WHERE project_id = :pid AND id = :id AND case_id IS NULL")
                        .param("caseId", caseId)
                        .param("now", now)
                        .param("pid", projectId)
                        .param("id", findingId)
                        .update()
                == 1;
    }

    /** Close one finding directly — no ruling, no human decision. Returns 0 when it was already closed. */
    public int close(String projectId, String findingId, String now) {
        return jdbc.sql("UPDATE finding SET status = 'closed', updated_at = :now"
                        + " WHERE project_id = :pid AND id = :id AND " + LIVE)
                .param("now", now)
                .param("pid", projectId)
                .param("id", findingId)
                .update();
    }

    /**
     * Close every open finding linked to a case, in the same transaction as the case's own close — a
     * case resolved or absorbed closes what it holds, whoever or whatever closed it.
     */
    public int closeByCase(String projectId, String caseId, String now) {
        return jdbc.sql("UPDATE finding SET status = 'closed', updated_at = :now"
                        + " WHERE project_id = :pid AND case_id = :caseId AND " + LIVE)
                .param("now", now)
                .param("pid", projectId)
                .param("caseId", caseId)
                .update();
    }

    /**
     * Close every open behaviour-drift finding whose cause is the graduated gram — its alerts must stop
     * by themselves. Matched on the profile subject plus the classifier's own key inside the payload,
     * because the scoped {@code cause_key} folds the cause kind in and a graduation is about the gram
     * whichever kind fired on it. Not a ruling: no verdict, no case, just a settled cause.
     */
    public int closeForNativeCause(String projectId, String profileId, String nativeCauseKey) {
        return jdbc.sql("UPDATE finding SET status = 'closed', updated_at = :now"
                        + " WHERE project_id = :pid AND subject_kind = :subjectKind AND subject_id = :profileId"
                        + "   AND payload ->> 'native_cause_key' = :cause AND " + LIVE)
                .param("now", java.time.Instant.now().toString())
                .param("pid", projectId)
                .param("subjectKind", FindingRow.SubjectKind.BEHAVIOR_PROFILE)
                .param("profileId", profileId)
                .param("cause", nativeCauseKey)
                .update();
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
                        // The same gate CaseOpener applies before a case ever opens, and deliberately the
                        // same one: a finding too unconfirmed to open a case is too unconfirmed to disqualify a day's
                        // traffic
                        // from being normal. Layer 1 detects change and cannot tell change from a
                        // problem, so excluding on an untriaged finding would blind the control to every
                        // legitimate shift the product ever makes. No horizon needed: closing a case
                        // (absorb or resolve) closes its findings, so their days re-enter the control the
                        // moment the finding leaves this set.
                        + "   AND triage_verdict = '" + FindingRow.TriageVerdict.POSITIVE + "'")
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

    private static Recorded recorded(ResultSet rs) throws SQLException {
        return new Recorded(rs.getString("id"), false, rs.getLong("sample_count"), rs.getString("escalated_at"));
    }

    private static Recorded created(String id, Recorded outcome) {
        return new Recorded(
                outcome.findingId(), id.equals(outcome.findingId()), outcome.sampleCount(), outcome.escalatedAt());
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
                rs.getString("case_id"),
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
