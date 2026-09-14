// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The aggregates Malformed Output's rate is recomputed from: per call site and per hour, how many outputs the
 * sweep checked against a schema and how many of those failed.
 *
 * <p><b>Recomputed from source on every pass, never accumulated.</b> The sweep writes a detection only for an
 * output that failed; the outputs that passed leave no row. So the denominator is counted from the spans
 * themselves, which is also what makes a re-run harmless: the counts are read, not added to, the property
 * {@code ToolErrorRepository#hourlyTallies} holds for the same reason.
 */
@Repository
public class MalformedOutputRateRepository {

    private final JdbcClient jdbc;

    private final ToolErrorStateRepository states;

    public MalformedOutputRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.states = ToolErrorStateRepository.forTable(jdbc, "malformed_output_state", "call_site_id");
    }

    /** The CUSUM state tool_error's engine carries between passes, one row per call site. */
    public ToolErrorStateRepository states() {
        return states;
    }

    /**
     * Checked and failed outputs per call site per hour since {@code from}, oldest first, as the tallies
     * {@code ToolErrorTrend} replays, keyed on the call site.
     *
     * <p>A span counts as checked when the detector would have validated it: its call site carries a schema
     * and its output is not empty. The preview column stands in for the output so this never reads a payload;
     * the writer cuts it from the same text and leaves it null exactly when the output is.
     *
     * <p><b>Only spans stored before {@code checkedBefore}.</b> The sweep checks in storage order, so a span
     * stored after its cursor is one it has not reached, and counting it would add a pass it never recorded.
     * Hours are on the span's own clock, because a CUSUM fed out of order is not a CUSUM.
     */
    public List<HourlyToolTally> hourlyTallies(
            String projectId, String classifierId, Instant from, String checkedBefore) {
        return jdbc.sql("""
                        SELECT date_trunc('hour', s.started_at) AS bucket,
                               s.call_site_id                   AS call_site_id,
                               COUNT(*)                         AS checked,
                               COUNT(d.id)                      AS malformed
                          FROM span s
                          JOIN call_site cs
                            ON cs.project_id = s.project_id AND cs.id = s.call_site_id
                           AND cs.output_schema IS NOT NULL
                          LEFT JOIN malformed_output_detection d
                            ON d.project_id = s.project_id AND d.classifier_id = :cid
                           AND d.subject_trace_id = s.trace_id AND d.subject_span_id = s.id
                         WHERE s.project_id = :pid
                           AND s.started_at >= CAST(:from AS timestamptz)
                           AND s.created_at < CAST(:checkedBefore AS timestamptz)
                           AND s.output_preview IS NOT NULL AND s.output_preview <> ''
                         GROUP BY 1, 2
                         ORDER BY 1 ASC, 2 ASC
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("from", from.toString())
                .param("checkedBefore", checkedBefore)
                .query((rs, n) -> new HourlyToolTally(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        rs.getString("call_site_id"),
                        rs.getLong("checked"),
                        rs.getLong("malformed")))
                .list();
    }

    /** The bucket a not-JSON detection's failures count under: the whole output, no schema field. */
    public static final String FIELD_NOT_JSON = "not_json";

    /**
     * The bucket a detection written before the structured-violation rework counts under: its {@code
     * violations} array holds plain strings, not {@code {field, keyword, message}} objects, so which
     * declared field it hit cannot be recovered.
     */
    public static final String FIELD_OTHER = "other";

    /**
     * A detection's evidence matches {@code :field} when it is the not-JSON bucket, the pre-rework
     * catch-all, or a structured violation whose own {@code field} equals it. Shared between {@link
     * #fieldFailureCounts} and {@link #failingOutputs} so the two can never disagree about which
     * detections a field owns.
     */
    private static final String FIELD_MATCH = """
            ( (:field = 'not_json' AND d.evidence->>'reason' = 'not_json')
           OR (:field <> 'not_json' AND EXISTS (
                 SELECT 1 FROM jsonb_array_elements(COALESCE(d.evidence->'violations', '[]'::jsonb)) v(value)
                WHERE (:field = 'other' AND jsonb_typeof(v.value) <> 'object')
                   OR (:field <> 'other' AND jsonb_typeof(v.value) = 'object' AND v.value ->> 'field' = :field)
              )) )
            """;

    /**
     * Distinct failing outputs since {@code since}, one count per declared field plus the {@link
     * #FIELD_NOT_JSON} and {@link #FIELD_OTHER} buckets — what "How outputs broke" annotates the schema
     * tree with. A span counts once per field even if the same violation fired on it more than once in
     * its history, and once under EACH field it violates, since one output can break more than one field.
     */
    private record FieldCount(String field, long failing) {}

    public Map<String, Long> fieldFailureCounts(
            String projectId, String classifierId, String callSiteId, String since) {
        List<FieldCount> rows = jdbc.sql("""
                        SELECT
                          CASE
                            WHEN d.evidence ->> 'reason' = 'not_json' THEN 'not_json'
                            WHEN jsonb_typeof(v.value) = 'object' THEN COALESCE(v.value ->> 'field', 'other')
                            ELSE 'other'
                          END AS field,
                          COUNT(DISTINCT (d.subject_trace_id, d.subject_span_id)) AS failing
                        FROM malformed_output_detection d
                        JOIN span s
                          ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id AND s.id = d.subject_span_id
                        LEFT JOIN LATERAL jsonb_array_elements(COALESCE(d.evidence -> 'violations', '[]'::jsonb)) v(value)
                          ON TRUE
                        WHERE d.project_id = :pid AND d.classifier_id = :cid AND s.call_site_id = :callSite
                          AND s.started_at >= CAST(:since AS timestamptz)
                        GROUP BY 1
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("since", since)
                .query((rs, n) -> new FieldCount(rs.getString("field"), rs.getLong("failing")))
                .list();
        Map<String, Long> out = new LinkedHashMap<>();
        for (FieldCount row : rows) out.put(row.field(), row.failing());
        return out;
    }

    /**
     * One failing output: enough to fetch its payload and place it on a trace. {@code detectionId} is
     * {@code malformed_output_detection.id}, carried only to reseed {@link Cursor} — a span id is unique
     * within its trace but not across the whole population this pages over. {@code evidenceJson} is the
     * detection's own {@code {reason, violations}} blob, read back for the field's own violation message.
     */
    public record DetectionRow(
            String traceId,
            String spanId,
            @Nullable String name,
            String startedAt,
            String detectionId,
            @Nullable String evidenceJson) {}

    /** A page of {@link DetectionRow}s, the population size the field owns, and the cursor past this page. */
    public record DetectionPage(
            List<DetectionRow> rows, long total, @Nullable String nextCursor) {}

    /**
     * One field's failing outputs since {@code since}, newest first — what a reader pages through after
     * selecting a schema field (or {@link #FIELD_NOT_JSON} / {@link #FIELD_OTHER}) on the finding page.
     *
     * <p>Keyset on {@code (started_at, id)}, over-fetched by one exactly as {@link
     * ai.tessary.classifier.finding.FindingEvidenceRepository#spanPage} is: a next page needs no separate
     * count. {@link #total} is a second, uncursored read of the same {@link #FIELD_MATCH} population — it
     * rides every page because a reader mid-page ("i of n") needs it beside the row they're looking at.
     */
    public DetectionPage failingOutputs(
            String projectId,
            String classifierId,
            String callSiteId,
            String since,
            String field,
            int limit,
            @Nullable String cursor) {
        long total = jdbc.sql("SELECT COUNT(*) FROM malformed_output_detection d "
                        + "JOIN span s ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id "
                        + "AND s.id = d.subject_span_id "
                        + "WHERE d.project_id = :pid AND d.classifier_id = :cid AND s.call_site_id = :callSite "
                        + "AND s.started_at >= CAST(:since AS timestamptz) AND " + FIELD_MATCH)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("since", since)
                .param("field", field)
                .query(Long.class)
                .single();

        Cursor key = Cursor.decode(cursor);
        StringBuilder sql = new StringBuilder(
                "SELECT d.id, d.subject_trace_id, d.subject_span_id, s.name, s.started_at, d.evidence::text AS evidence "
                        + "FROM malformed_output_detection d "
                        + "JOIN span s ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id "
                        + "AND s.id = d.subject_span_id "
                        + "WHERE d.project_id = :pid AND d.classifier_id = :cid AND s.call_site_id = :callSite "
                        + "AND s.started_at >= CAST(:since AS timestamptz) AND "
                        + FIELD_MATCH);
        if (key != null) {
            sql.append(" AND (s.started_at, d.id) < (CAST(:cStartedAt AS timestamptz), :cId)");
        }
        sql.append(" ORDER BY s.started_at DESC, d.id DESC LIMIT :n");

        var spec = jdbc.sql(sql.toString())
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("since", since)
                .param("field", field)
                .param("n", limit + 1);
        if (key != null) {
            spec = spec.param("cStartedAt", key.startedAt()).param("cId", key.id());
        }
        List<DetectionRow> rows = spec.query((rs, n) -> new DetectionRow(
                        rs.getString("subject_trace_id"),
                        rs.getString("subject_span_id"),
                        rs.getString("name"),
                        rs.getObject("started_at", OffsetDateTime.class)
                                .toInstant()
                                .toString(),
                        rs.getString("id"),
                        rs.getString("evidence")))
                .list();
        if (rows.size() <= limit) return new DetectionPage(rows, total, null);
        List<DetectionRow> page = rows.subList(0, limit);
        return new DetectionPage(List.copyOf(page), total, Cursor.encode(page.get(page.size() - 1)));
    }

    private record Cursor(String startedAt, String id) {
        static @Nullable Cursor decode(@Nullable String cursor) {
            if (cursor == null || cursor.isBlank()) return null;
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int sep = raw.indexOf('');
                if (sep < 0) return null;
                return new Cursor(raw.substring(0, sep), raw.substring(sep + 1));
            } catch (RuntimeException e) {
                return null;
            }
        }

        static String encode(DetectionRow last) {
            String raw = last.startedAt() + '' + last.detectionId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * The most recent failing spans at one call site since a spell began, newest first: instances a reader can
     * open, not the population.
     */
    public List<FindingEvidenceRepository.Ref> recentFailures(
            String projectId, String classifierId, String callSiteId, String since, int limit) {
        return jdbc.sql("""
                        SELECT d.subject_trace_id, d.subject_span_id
                          FROM malformed_output_detection d
                          JOIN span s
                            ON s.project_id = d.project_id AND s.trace_id = d.subject_trace_id
                           AND s.id = d.subject_span_id
                         WHERE d.project_id = :pid AND d.classifier_id = :cid AND s.call_site_id = :callSite
                           AND s.started_at >= CAST(:since AS timestamptz)
                         ORDER BY s.started_at DESC
                         LIMIT :limit
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .param("callSite", callSiteId)
                .param("since", since)
                .param("limit", limit)
                .query((rs, n) -> FindingEvidenceRepository.Ref.span(
                        rs.getString("subject_trace_id"), rs.getString("subject_span_id")))
                .list();
    }
}
