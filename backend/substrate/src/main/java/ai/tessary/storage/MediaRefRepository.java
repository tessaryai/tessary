// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code media_ref} join table: makes an {@code image_ref} string inside a payload visible to the database, so
 * {@code RetentionRepository.deleteOrphanedMedia} can collect unreferenced bytes. The FK is to {@code span_payload}, ON
 * DELETE CASCADE, because media lives exactly as long as the payload.
 */
@Repository
public class MediaRefRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public MediaRefRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** Idempotent on the full key. Must run after the payload row exists, because of the FK. */
    public void insertAll(String projectId, String traceId, String spanId, List<String> mediaIds) {
        for (String mediaId : mediaIds) {
            jdbc.sql("""
                            INSERT INTO media_ref (project_id, media_id, trace_id, span_id)
                            VALUES (:pid, :mediaId, :traceId, :spanId)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("pid", projectId)
                    .param("mediaId", mediaId)
                    .param("traceId", traceId)
                    .param("spanId", spanId)
                    .update();
        }
    }

    public record SpanMedia(String traceId, String spanId, List<String> mediaIds) {}

    /** Same key and idempotence as {@link #insertAll}. */
    public void insertAllForSpans(String projectId, List<SpanMedia> spans) {
        List<SqlParameterSource> rows = new ArrayList<>();
        for (SpanMedia span : spans) {
            for (String mediaId : span.mediaIds()) {
                rows.add(new MapSqlParameterSource()
                        .addValue("pid", projectId)
                        .addValue("mediaId", mediaId)
                        .addValue("traceId", span.traceId())
                        .addValue("spanId", span.spanId()));
            }
        }
        if (rows.isEmpty()) return;
        BatchCounts.requireReal(named.batchUpdate("""
                INSERT INTO media_ref (project_id, media_id, trace_id, span_id)
                VALUES (:pid, :mediaId, :traceId, :spanId)
                ON CONFLICT DO NOTHING
                """, rows.toArray(SqlParameterSource[]::new)));
    }
}
