// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.endusers.model.PageState;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one {@code end_user_project} row of a project: the salt of its user keys, the fixed history start day, the
 * watermarks of the hit sweep and the fold, and the last two page states. Every method creates the row on first use
 * with a fresh salt; a concurrent first use keeps the salt that was written first.
 */
@Repository
public class EndUserProjectRepository {

    public static final int SALT_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public EndUserProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code pageStateMemory} is oldest first, at most two. */
    public record State(
            @Nullable Integer historyStartDay,
            @Nullable Instant hitsWatermark,
            @Nullable Instant foldCreatedWatermark,
            @Nullable Instant foldRollupWatermark,
            List<PageState> pageStateMemory) {}

    public byte[] salt(String projectId) {
        ensure(projectId);
        return jdbc.sql("SELECT salt FROM end_user_project WHERE project_id = :pid")
                .param("pid", projectId)
                .query((rs, n) -> rs.getBytes("salt"))
                .single();
    }

    public State state(String projectId) {
        ensure(projectId);
        return jdbc.sql("""
                SELECT history_start_day, hits_watermark, fold_created_watermark, fold_rollup_watermark,
                       ARRAY(SELECT m.state
                               FROM jsonb_array_elements_text(page_state_memory) WITH ORDINALITY AS m(state, n)
                              ORDER BY m.n) AS page_state_memory
                  FROM end_user_project
                 WHERE project_id = :pid
                """).param("pid", projectId).query((rs, n) -> map(rs)).single();
    }

    /** Sets the history start day ({@code t0}) once; later calls leave it and return the stored day. */
    public int fixHistoryStartDay(String projectId, int day) {
        ensure(projectId);
        return jdbc.sql("""
                UPDATE end_user_project
                   SET history_start_day = COALESCE(history_start_day, :day), updated_at = now()
                 WHERE project_id = :pid
             RETURNING history_start_day
                """)
                .param("pid", projectId)
                .param("day", day)
                .query(Integer.class)
                .single();
    }

    public void setHitsWatermark(String projectId, Instant at) {
        ensure(projectId);
        jdbc.sql("UPDATE end_user_project SET hits_watermark = :at, updated_at = now() WHERE project_id = :pid")
                .param("pid", projectId)
                .param("at", Timestamp.from(at))
                .update();
    }

    public void setFoldWatermarks(String projectId, Instant created, Instant rollup) {
        ensure(projectId);
        jdbc.sql("""
                UPDATE end_user_project
                   SET fold_created_watermark = :created, fold_rollup_watermark = :rollup, updated_at = now()
                 WHERE project_id = :pid
                """)
                .param("pid", projectId)
                .param("created", Timestamp.from(created))
                .param("rollup", Timestamp.from(rollup))
                .update();
    }

    /** Appends the page state of a judge run and drops all but the last two. */
    public void rememberPageState(String projectId, PageState state) {
        ensure(projectId);
        jdbc.sql("""
                UPDATE end_user_project
                   SET page_state_memory = CASE WHEN jsonb_array_length(page_state_memory) = 0
                                                THEN jsonb_build_array(CAST(:state AS text))
                                                ELSE jsonb_build_array(page_state_memory -> -1, CAST(:state AS text))
                                           END,
                       updated_at = now()
                 WHERE project_id = :pid
                """).param("pid", projectId).param("state", state.wire()).update();
    }

    private void ensure(String projectId) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        jdbc.sql(
                        "INSERT INTO end_user_project (project_id, salt) VALUES (:pid, :salt) ON CONFLICT (project_id) DO NOTHING")
                .param("pid", projectId)
                .param("salt", salt)
                .update();
    }

    private static State map(ResultSet rs) throws SQLException {
        int day = rs.getInt("history_start_day");
        Integer historyStartDay = rs.wasNull() ? null : day;
        String[] memory = (String[]) rs.getArray("page_state_memory").getArray();
        return new State(
                historyStartDay,
                instant(rs, "hits_watermark"),
                instant(rs, "fold_created_watermark"),
                instant(rs, "fold_rollup_watermark"),
                Arrays.stream(memory).map(PageState::fromWire).toList());
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }
}
