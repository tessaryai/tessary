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
 * The {@code finding} table every classifier files into. Every upsert conflicts on the SAME partial index,
 * {@code ux_finding_live}, whose predicate is restated literally because Postgres matches a partial index by
 * expression. A ruled finding leaves the index, so the next firing inserts a fresh row instead of mutating a settled
 * one. Each {@code record*} reports whether it created the finding, so a cause escalates once.
 */
@Repository
public class FindingRepository {

    private static final String COLS = "id, project_id, classifier_key, cause_key, subject_kind, subject_id, "
            + "subject_label, call_site_id, status, onset_at, last_seen_at, title, basis, severity, "
            + "sample_count, payload, evidence_counts, since_version_id, escalated_at, triage_verdict, "
            + "triage_action, triage_summary, triage_citations, triaged_at, human_verdict_at, "
            + "case_id, created_at, updated_at";

    /** No {@code triage_*} columns, on purpose; see {@link FindingClaim}. */
    private static final String CLAIM_COLS = "id, project_id, classifier_key, cause_key, subject_kind, "
            + "subject_id, subject_label, call_site_id, onset_at, last_seen_at, title, basis, severity, "
            + "sample_count, payload, evidence_counts, created_at";

    /**
     * Shared by {@link #listAutoEscalatable} and {@link #escalationCandidatesByKey} so they cannot disagree on
     * eligibility.
     */
    private static final String AUTO_ESCALATABLE = "   AND f.status = '" + FindingRow.Status.OPEN + "'"
            + "   AND f.escalated_at IS NULL"
            + "   AND f.human_verdict_at IS NULL"
            + "   AND f.triage_verdict IS NULL"
            + "   AND f.sample_count >= :minCount"
            + "   AND EXISTS (SELECT 1 FROM finding_evidence e"
            + "                WHERE e.finding_id = f.id AND e.trace_id IS NOT NULL)";

    private static final String LIVE = "status = 'open'";

    /** The arbiter every upsert conflicts on, restated literally because a partial index is matched by expression. */
    private static final String OPEN_UNRULED = "status = 'open' AND triage_verdict IS NULL";

    private final JdbcClient jdbc;

    public FindingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The telemetry heartbeat's {@code counts.findings} (devdocs/reference/telemetry-contract.md §1). */
    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM finding").query(Long.class).single();
    }

    /**
     * {@code sampleCount} is the cause's running total, which the recurrence gate reads. {@code escalatedAt} is
     * explicit because triage happens on the sweep that crosses the recurrence bar, not the one that opened the cause.
     */
    public record Recorded(
            String findingId,
            boolean created,
            long sampleCount,
            @Nullable String escalatedAt,
            /**
             * The write set the finding's onset: on create, or on a {@link #recordShift} across a recovery. Evidence
             * tied to the onset is written only then.
             */
            boolean onsetSet) {

        public Recorded(String findingId, boolean created, long sampleCount, @Nullable String escalatedAt) {
            this(findingId, created, sampleCount, escalatedAt, created);
        }
    }

    /**
     * Record a closed window's shift. {@code sampleDelta} is traffic observed, not traces labelled. The payload
     * refreshes every window because absorb absorbs the current level. {@code onsetAt} is the event time of the
     * window's change point and {@code eventAt} that of its last row; {@code quietBefore} is the recovery horizon, past
     * which a new spell's onset moves so {@code CaseLedger.isNewSpell} can reopen its case. The baseline declaration
     * describes rows written once, so it is kept across refreshes. The change point is kept while the onset is, so the
     * two always agree.
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
            String onsetAt,
            String eventAt,
            String quietBefore,
            String now) {
        String payload = mergeVocabulary(evidenceJson, nativeVocabulary("distribution_shift", "__global__", causeKey));
        // The new payload with the old baseline declaration, which describes rows written once.
        String keptBaseline = """
            CASE WHEN finding.payload #> '{control,baseline}' IS NOT NULL
                 THEN jsonb_set(EXCLUDED.payload, '{control,baseline}', finding.payload #> '{control,baseline}')
                 ELSE EXCLUDED.payload END""";
        record Shift(Recorded recorded, boolean onsetSet) {}
        Shift outcome = jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 call_site_id, status, onset_at, last_seen_at, sample_count, payload,
                                 since_version_id, created_at, updated_at)
            VALUES (:id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :callSiteId,
                    'open', :onsetAt, :eventAt, :delta, CAST(:payload AS jsonb), :versionId, :now, :now)
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status = 'open' AND triage_verdict IS NULL DO UPDATE SET
                sample_count = finding.sample_count + EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                -- The conflict target only ever matches an unruled row (see the class javadoc), so the
                -- payload here is always safe to re-point: nothing has read and ruled on this claim yet.
                -- The change point moves with the onset, so within a spell the old one is kept.
                payload = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN %1$s
                    WHEN finding.payload -> 'changepoint' IS NULL THEN (%1$s) - 'changepoint'
                    ELSE jsonb_set(%1$s, '{changepoint}', finding.payload -> 'changepoint') END,
                -- The onset moves only across an observed recovery, never within a spell. Both sides are
                -- now EVENT time.
                onset_at = CASE
                    WHEN finding.last_seen_at < :quietBefore THEN EXCLUDED.onset_at
                    ELSE finding.onset_at END
            RETURNING id, sample_count, escalated_at, onset_at = :onsetAt AS onset_set
            """.formatted(keptBaseline))
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
                .param("onsetAt", onsetAt)
                .param("eventAt", eventAt)
                .param("quietBefore", quietBefore)
                .param("now", now)
                .query((rs, n) -> new Shift(recorded(rs), rs.getBoolean("onset_set")))
                .single();
        Recorded made = created(id, outcome.recorded());
        return new Recorded(
                made.findingId(),
                made.created(),
                made.sampleCount(),
                made.escalatedAt(),
                made.created() || outcome.onsetSet());
    }

    /**
     * The tool-error write, which ASSIGNS counts rather than accumulating them: the numbers are recomputed from an
     * hourly aggregate on every read, so the write is idempotent (tool-error.md §5.1). Observations refresh;
     * {@code triage_*}, {@code escalated_at}, {@code human_verdict_at} and {@code status} never do.
     */
    public @Nullable Recorded recordRecomputedCause(
            String id,
            String projectId,
            String causeKey,
            long observedCount,
            @Nullable String callSiteId,
            @Nullable String onsetAt,
            String evidenceJson,
            String eventAt,
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
                null,
                observedCount,
                callSiteId,
                onsetAt,
                evidenceJson,
                eventAt,
                quietBefore,
                now);
    }

    /**
     * For any classifier that recomputes a rate from an hourly aggregate on every pass. A window no newer than a ruled
     * finding opens nothing: the ruled row left {@code ux_finding_live}, so without this guard the next pass would
     * insert a duplicate of a finding someone just ruled on. Returns null when suppressed.
     */
    public @Nullable Recorded recordRecomputedRate(
            String id,
            String projectId,
            String classifierKey,
            String causeKey,
            String causeKind,
            String nativeCauseKey,
            String subjectKind,
            String subjectId,
            String subjectLabel,
            @Nullable String title,
            long observedCount,
            @Nullable String callSiteId,
            @Nullable String onsetAt,
            String evidenceJson,
            String eventAt,
            String quietBefore,
            String now) {
        String payload = mergeVocabulary(evidenceJson, nativeVocabulary(causeKind, "", nativeCauseKey));
        return jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 subject_label, call_site_id, status, onset_at, last_seen_at, title,
                                 sample_count, payload, created_at, updated_at)
            SELECT :id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :subjectLabel, :callSiteId,
                   'open', :onsetAt, :eventAt, :title, :count, CAST(:payload AS jsonb), :now, :now
             WHERE EXISTS (SELECT 1 FROM finding
                            WHERE project_id = :pid AND classifier_key = :classifier AND cause_key = :causeKey
                              AND status = 'open' AND triage_verdict IS NULL)
                OR NOT EXISTS (SELECT 1 FROM finding
                            WHERE project_id = :pid AND classifier_key = :classifier AND cause_key = :causeKey
                              AND (status = 'closed' OR triage_verdict IS NOT NULL)
                              AND CAST(last_seen_at AS timestamptz) >= CAST(:eventAt AS timestamptz))
            ON CONFLICT (project_id, classifier_key, cause_key)
                WHERE status = 'open' AND triage_verdict IS NULL DO UPDATE SET
                sample_count = EXCLUDED.sample_count,
                last_seen_at = EXCLUDED.last_seen_at,
                updated_at = EXCLUDED.updated_at,
                -- The conflict target only ever matches an unruled row — see recordShift.
                payload = EXCLUDED.payload,
                -- A title with the spell's rates in it follows the spell, as the payload does.
                title = EXCLUDED.title,
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
                .param("title", title)
                .param("count", observedCount)
                .param("payload", payload)
                .param("onsetAt", onsetAt == null ? eventAt : onsetAt)
                .param("eventAt", eventAt)
                .param("quietBefore", quietBefore)
                .param("now", now)
                .query((rs, n) -> recorded(rs))
                .optional()
                .map(outcome -> created(id, outcome))
                .orElse(null);
    }

    /**
     * For a detector that rules its own findings at filing (Frustration): {@code ux_finding_live} never holds such a
     * row.
     */
    public Optional<FindingRow> findOpenByCause(String projectId, String classifierKey, String causeKey) {
        return jdbc.sql("SELECT " + COLS + " FROM finding"
                        + " WHERE project_id = :pid AND classifier_key = :classifier AND cause_key = :causeKey"
                        + "   AND status = 'open' AND triage_verdict = '" + FindingRow.TriageVerdict.POSITIVE + "'"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1")
                .param("pid", projectId)
                .param("classifier", classifierKey)
                .param("causeKey", causeKey)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * The row {@link #recordRecomputedRate}'s conflict target refreshes; {@link #findOpenByCause} is the ruled
     * counterpart.
     */
    public Optional<FindingRow> findLiveByCause(String projectId, String classifierKey, String causeKey) {
        return jdbc.sql("SELECT " + COLS + " FROM finding"
                        + " WHERE project_id = :pid AND classifier_key = :classifier AND cause_key = :causeKey"
                        + "   AND status = 'open' AND triage_verdict IS NULL")
                .param("pid", projectId)
                .param("classifier", classifierKey)
                .param("causeKey", causeKey)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * Refresh a ruled, still-open finding's count, clock and payload, never the ruling. The payload is merged over the
     * stored one and {@code last_seen_at} only moves forward. Returns 0 when closed or never ruled.
     */
    public int refreshRuledObservation(
            String projectId, String findingId, long observedCount, String payloadJson, String eventAt, String now) {
        return jdbc.sql("""
                UPDATE finding
                   SET sample_count = :count,
                       payload = COALESCE(payload, '{}'::jsonb) || CAST(:payload AS jsonb),
                       last_seen_at = CASE
                           WHEN last_seen_at IS NULL
                                OR CAST(:eventAt AS timestamptz) > CAST(last_seen_at AS timestamptz)
                           THEN :eventAt ELSE last_seen_at END,
                       updated_at = :now
                 WHERE project_id = :pid AND id = :id AND status = 'open' AND triage_verdict IS NOT NULL
                """)
                .param("count", observedCount)
                .param("payload", payloadJson)
                .param("eventAt", eventAt)
                .param("now", now)
                .param("pid", projectId)
                .param("id", findingId)
                .update();
    }

    /**
     * One live finding per classifier: {@code classifierId} is both the subject and the cause scope. See
     * {@link #armedUpsert}.
     */
    public @Nullable Recorded recordArmedWindow(
            String id,
            String projectId,
            String classifierKey,
            String classifierId,
            String label,
            long observedCount,
            @Nullable String callSiteId,
            String payloadJson,
            String onsetAt,
            String lastSeenAt,
            String quietBefore,
            String now) {
        return armedUpsert(
                id,
                projectId,
                classifierKey,
                CauseKey.perSpanClassifier(classifierId),
                classifierId,
                label,
                callSiteId,
                observedCount,
                onsetAt,
                lastSeenAt,
                payloadJson,
                quietBefore,
                now);
    }

    /** One finding per call site and facet. See {@link #armedUpsert}. */
    public @Nullable Recorded recordArmedFacet(
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
        return armedUpsert(
                id,
                projectId,
                classifierKey,
                CauseKey.perSpanClassifierFacet(classifierId, callSiteId, facet),
                classifierId,
                label,
                callSiteId,
                observedCount,
                onsetAt,
                lastSeenAt,
                payloadJson,
                quietBefore,
                now);
    }

    /**
     * Every field moves forward only, since a late upload can report an older window; {@code onset_at} starts a new
     * spell only when a NEWER window follows a quiet gap. A window no newer than a ruled finding (of either sign) opens
     * nothing, or a re-sweep would insert a duplicate. Comparisons cast to {@code timestamptz}:
     * {@code Instant#toString} drops a zero fraction, so text order is wrong.
     */
    private @Nullable Recorded armedUpsert(
            String id,
            String projectId,
            String classifierKey,
            String causeKey,
            String classifierId,
            String label,
            @Nullable String callSiteId,
            long observedCount,
            String onsetAt,
            String lastSeenAt,
            String payloadJson,
            String quietBefore,
            String now) {
        return jdbc.sql("""
            INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id,
                                 subject_label, call_site_id, status, onset_at, last_seen_at,
                                 sample_count, payload, created_at, updated_at)
            SELECT :id, :pid, :classifier, :causeKey, :subjectKind, :subjectId, :subjectLabel, :callSiteId,
                   'open', :onsetAt, :lastSeenAt, :count, CAST(:payload AS jsonb), :now, :now
             WHERE EXISTS (SELECT 1 FROM finding
                            WHERE project_id = :pid AND classifier_key = :classifier AND cause_key = :causeKey
                              AND status = 'open' AND triage_verdict IS NULL)
                OR NOT EXISTS (SELECT 1 FROM finding
                            WHERE project_id = :pid AND classifier_key = :classifier AND cause_key = :causeKey
                              AND (status = 'closed' OR triage_verdict IS NOT NULL)
                              AND CAST(last_seen_at AS timestamptz) >= CAST(:onsetAt AS timestamptz))
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
                .param("causeKey", causeKey)
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
                .optional()
                .map(outcome -> created(id, outcome))
                .orElse(null);
    }

    /** Conditional on {@code escalated_at IS NULL} so two racing sweeps enqueue exactly one microVM. */
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
     * Action and status are derived here so the mapping ({@link FindingRow.TriageAction#of}) has one implementation;
     * {@code finding_triage_paired_check} enforces they land together. The {@link #OPEN_UNRULED} guard is what freezes
     * a ruled finding: zero rows means another ruling won the race.
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
     * Same guard as {@link #recordTriage}: zero rows becomes a {@code 409 FINDING_CLOSED} rather than overwriting the
     * first ruling.
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
     * Both filters are in SQL so the row limit applies per scope: a busy scope could otherwise exhaust a project-wide
     * LIMIT and a quiet one would render "no findings".
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
        // In SQL so the row limit bounds what is SHOWN. Every source of a ruling writes triage_verdict.
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

    public long countOpen(String projectId) {
        return jdbc.sql("SELECT count(*) FROM finding WHERE project_id = :pid AND " + LIVE)
                .param("pid", projectId)
                .query(Long.class)
                .single();
    }

    /** Ids only, so the RCA lane can widen its subject without reading a column {@link #findClaim} hides from it. */
    public List<String> caseSiblingIds(String projectId, String findingId) {
        return jdbc.sql("""
                        SELECT f.id FROM finding f
                        JOIN finding anchor ON anchor.project_id = f.project_id AND anchor.case_id = f.case_id
                        WHERE f.project_id = :pid AND anchor.id = :id
                        ORDER BY f.created_at, f.id
                        """)
                .param("pid", projectId)
                .param("id", findingId)
                .query(String.class)
                .list();
    }

    public List<FindingRow> listByCase(String projectId, String caseId) {
        return jdbc.sql("SELECT " + COLS + " FROM finding WHERE project_id = :pid AND case_id = :caseId"
                        + " ORDER BY created_at DESC")
                .param("pid", projectId)
                .param("caseId", caseId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Guarded on {@code case_id IS NULL}: a case that lost a race cannot steal a finding another case holds. */
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

    /** A ruled finding is someone's decision, and a positive one backs a case, so those stay. */
    public int closeUnruled(String projectId, String classifierKey, String now) {
        return jdbc.sql("UPDATE finding SET status = 'closed', updated_at = :now"
                        + " WHERE project_id = :pid AND classifier_key = :key AND " + LIVE
                        + " AND triage_verdict IS NULL")
                .param("now", now)
                .param("pid", projectId)
                .param("key", classifierKey)
                .update();
    }

    public int closeByCase(String projectId, String caseId, String now) {
        return jdbc.sql("UPDATE finding SET status = 'closed', updated_at = :now"
                        + " WHERE project_id = :pid AND case_id = :caseId AND " + LIVE)
                .param("now", now)
                .param("pid", projectId)
                .param("caseId", caseId)
                .update();
    }

    /**
     * Open, never escalated, never ruled by a human, still citing evidence, observed at least {@code minSampleCount}
     * times, worst-first. {@code escalated_at} is set by the same {@link #markEscalated} as a hand-press, so the two
     * cannot double-spend. The evidence check is role-agnostic: naming one trace as the way in would bias the run.
     */
    public List<FindingRow> listAutoEscalatable(
            String projectId, Collection<String> classifierKeys, long minSampleCount, int limit) {
        if (classifierKeys.isEmpty()) return List.of();
        return jdbc.sql("SELECT " + COLS + " FROM finding f"
                        + " WHERE f.project_id = :pid AND f.classifier_key IN (:classifiers)"
                        + AUTO_ESCALATABLE
                        + " ORDER BY f.sample_count DESC, f.onset_at ASC LIMIT :limit")
                .param("pid", projectId)
                .param("classifiers", classifierKeys)
                .param("minCount", minSampleCount)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * One row per key, so an un-owned key with many findings cannot fill a page before an owned one is seen. Never
     * called with no excluded keys (its caller always excludes the built-ins), which {@code NOT IN ()} could not express.
     */
    public List<FindingClaim> escalationCandidatesByKey(
            String projectId, Collection<String> excludedKeys, long minSampleCount) {
        return jdbc.sql("SELECT DISTINCT ON (f.classifier_key) " + CLAIM_COLS + " FROM finding f"
                        + " WHERE f.project_id = :pid AND f.classifier_key NOT IN (:excluded)"
                        + AUTO_ESCALATABLE
                        + " ORDER BY f.classifier_key, f.id")
                .param("pid", projectId)
                .param("excluded", excludedKeys)
                .param("minCount", minSampleCount)
                .query((rs, n) -> mapClaim(rs))
                .list();
    }

    /**
     * Keeps a confirmed regression OUT of the rolling control, which would otherwise let the shift under investigation
     * become the bar. One query per pass, consulted in memory.
     */
    public Map<String, List<ConfirmedSpan>> confirmedSpansBySubject(
            String projectId, Collection<String> classifierKeys) {
        if (classifierKeys.isEmpty()) return Map.of();
        record Row(String subjectId, ConfirmedSpan span) {}
        List<Row> rows = jdbc.sql("SELECT subject_id, onset_at, last_seen_at FROM finding"
                        + " WHERE project_id = :pid AND classifier_key IN (:classifiers) AND " + LIVE
                        // The same gate CaseOpener applies: excluding on an untriaged finding would blind the control
                        // to every legitimate shift. Closing a case closes its findings, so their days re-enter the
                        // control.
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
     * Left as text: every consumer compares against ISO text, and an Instant round trip would put a timezone in the
     * middle.
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
     * RCA's only door onto a finding. A column list rather than a filter on {@link #findById}, so no {@code triage_*}
     * value is ever in scope to leak into a dossier.
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

    /** Built as a literal rather than through Jackson: the values are ids and enum words. */
    private static Map<String, String> nativeVocabulary(String causeKind, String workflowKey, String nativeCauseKey) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("cause_kind", causeKind);
        out.put("workflow_key", workflowKey);
        out.put("native_cause_key", nativeCauseKey);
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

    /** The caller's JSON is spliced, keeping the detector's document byte-identical. */
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
