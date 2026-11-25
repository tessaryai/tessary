// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The deletes of the horizon sweep, and the one trace read that tells which readable IDs still have a trace. */
@Repository
public class HorizonSweepRepository {

    private static final long SECONDS_PER_DAY = 86_400;
    private static final List<String> DAY_TABLES =
            List.of("end_user_day", "end_user_day_null", "end_user_alias_edge", "end_user_hit", "end_user_fold_day");

    private final JdbcClient jdbc;

    public HorizonSweepRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes the day-keyed fact rows before {@code firstKeptDay}; returns the rows deleted. */
    public int deleteFactsBefore(String projectId, int firstKeptDay) {
        int deleted = 0;
        for (String table : DAY_TABLES) {
            deleted += jdbc.sql("DELETE FROM " + table + " WHERE project_id = :pid AND day < :day")
                    .param("pid", projectId)
                    .param("day", firstKeptDay)
                    .update();
        }
        return deleted;
    }

    /** Deletes the IDs last seen before {@code firstKeptDay}; returns how many. */
    public int deleteUsersBefore(String projectId, int firstKeptDay) {
        return jdbc.sql("DELETE FROM end_user WHERE project_id = :pid AND last_seen_day < :day")
                .param("pid", projectId)
                .param("day", firstKeptDay)
                .update();
    }

    /**
     * The raw user IDs of the traces that still exist and start before {@code day}: a range on
     * {@code ix_trace_project_started_id} that, once retention ran, holds only traces it keeps on purpose.
     */
    public List<String> traceUserIdsBefore(String projectId, int day) {
        return jdbc.sql("""
                SELECT DISTINCT user_id FROM trace
                 WHERE project_id = :pid AND started_at < :before AND user_id IS NOT NULL AND is_deleted IS NOT TRUE
                """)
                .param("pid", projectId)
                .param("before", Instant.ofEpochSecond(day * SECONDS_PER_DAY).atOffset(ZoneOffset.UTC))
                .query(String.class)
                .list();
    }

    /**
     * Clears the readable text of the IDs last seen before {@code day}, except the keys given in hex; the shape
     * signature and cluster hash stay. Returns how many.
     */
    public int clearIdTextBefore(String projectId, int day, Collection<String> keptKeysHex) {
        return jdbc.sql("""
                UPDATE end_user SET id_text = NULL, updated_at = now()
                 WHERE project_id = :pid AND id_text IS NOT NULL AND last_seen_day < :day
                   AND encode(user_key, 'hex') <> ALL(:kept)
                """)
                .param("pid", projectId)
                .param("day", day)
                .param("kept", keptKeysHex.toArray(String[]::new))
                .update();
    }
}
