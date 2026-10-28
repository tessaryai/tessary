// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Contract: {@code devdocs/concepts/tool-error.md} §5. */
@Repository
public class ToolErrorStateRepository {

    /** The reset note written when the scorer or the tuning changed under a key's state. */
    public static final String TUNING_CHANGED = "tuning changed";

    private final JdbcClient jdbc;
    private final String table;
    private final String keyColumn;

    @Autowired
    public ToolErrorStateRepository(JdbcClient jdbc) {
        this(jdbc, "tool_error_state", "tool_key");
    }

    private ToolErrorStateRepository(JdbcClient jdbc, String table, String keyColumn) {
        this.jdbc = jdbc;
        this.table = table;
        this.keyColumn = keyColumn;
    }

    /**
     * Names are interpolated, so they must be literal identifiers of a table this codebase owns, never request input.
     */
    public static ToolErrorStateRepository forTable(JdbcClient jdbc, String table, String keyColumn) {
        return new ToolErrorStateRepository(jdbc, table, keyColumn);
    }

    private String sql(String template) {
        return template.replace("{key}", keyColumn).replace("{table}", table);
    }

    /**
     * A delete, not {@link #resetAndRelearn}, whose {@code reset_at} fence would make a rebuild skip every earlier
     * bucket.
     */
    public int deleteAll(String projectId) {
        return jdbc.sql(sql("DELETE FROM {table} WHERE project_id = :pid"))
                .param("pid", projectId)
                .update();
    }

    public Map<String, CarriedState> byTool(String projectId) {
        return list(projectId).stream().collect(Collectors.toMap(CarriedState::toolKey, c -> c));
    }

    public List<CarriedState> list(String projectId) {
        return jdbc.sql(sql("""
                        SELECT {key} AS bucket_key, s_up, s_down, onset_up_at, onset_down_at,
                               calls_since_onset_up, calls_since_onset_down,
                               baseline_calls, baseline_failures, watermark_bucket, state_epoch,
                               pending_pin_by, pending_pin_at, reset_at, learned_through, learn_before
                          FROM {table}
                         WHERE project_id = :pid
                         ORDER BY {key}
                        """))
                .param("pid", projectId)
                .query((rs, n) -> {
                    long baselineCalls = rs.getLong("baseline_calls");
                    ToolErrorRate baseline = null;
                    if (!rs.wasNull()) {
                        baseline = new ToolErrorRate();
                        baseline.addCounts(baselineCalls, rs.getLong("baseline_failures"));
                    }
                    String watermark = rs.getString("watermark_bucket");
                    // Null on a row written before learned_through existed, which learned every hour it judged.
                    String learnedThrough = rs.getString("learned_through");
                    return new CarriedState(
                            rs.getString("bucket_key"),
                            new State(
                                    rs.getDouble("s_up"),
                                    rs.getDouble("s_down"),
                                    rs.getString("onset_up_at"),
                                    rs.getString("onset_down_at"),
                                    rs.getLong("calls_since_onset_up"),
                                    rs.getLong("calls_since_onset_down")),
                            baseline,
                            watermark,
                            rs.getString("state_epoch"),
                            rs.getString("pending_pin_by"),
                            rs.getString("pending_pin_at"),
                            rs.getString("reset_at"),
                            learnedThrough == null ? watermark : learnedThrough,
                            rs.getString("learn_before"));
                })
                .list();
    }

    /**
     * Leaves {@code reset_*}, {@code pending_pin_*} and {@code learn_before} alone: they record human decisions (or the
     * upgrade's fence) a sweep seconds later must not erase.
     */
    public void save(String projectId, CarriedState carried, String updatedAt) {
        State state = carried.state();
        ToolErrorRate baseline = carried.baseline();
        jdbc.sql(sql("""
                        INSERT INTO {table} (
                            project_id, {key}, s_up, s_down, onset_up_at, onset_down_at,
                            calls_since_onset_up, calls_since_onset_down,
                            baseline_calls, baseline_failures, watermark_bucket, state_epoch, learned_through,
                            updated_at)
                        VALUES (:pid, :tool, :sUp, :sDown, :onsetUp, :onsetDown, :callsUp, :callsDown,
                                :baseCalls, :baseFailures, :watermark, :epoch, :learnedThrough, :at)
                        ON CONFLICT (project_id, {key}) DO UPDATE SET
                            s_up = EXCLUDED.s_up,
                            s_down = EXCLUDED.s_down,
                            onset_up_at = EXCLUDED.onset_up_at,
                            onset_down_at = EXCLUDED.onset_down_at,
                            calls_since_onset_up = EXCLUDED.calls_since_onset_up,
                            calls_since_onset_down = EXCLUDED.calls_since_onset_down,
                            baseline_calls = EXCLUDED.baseline_calls,
                            baseline_failures = EXCLUDED.baseline_failures,
                            watermark_bucket = EXCLUDED.watermark_bucket,
                            state_epoch = EXCLUDED.state_epoch,
                            learned_through = EXCLUDED.learned_through,
                            updated_at = EXCLUDED.updated_at
                        """))
                .param("pid", projectId)
                .param("tool", carried.toolKey())
                .param("sUp", state.sUp())
                .param("sDown", state.sDown())
                .param("onsetUp", state.onsetUpAt())
                .param("onsetDown", state.onsetDownAt())
                .param("callsUp", state.callsSinceOnsetUp())
                .param("callsDown", state.callsSinceOnsetDown())
                .param("baseCalls", baseline == null ? null : baseline.calls())
                .param("baseFailures", baseline == null ? null : baseline.failures())
                .param("watermark", carried.watermarkBucket())
                .param("epoch", carried.stateEpoch())
                .param("learnedThrough", carried.learnedThrough())
                .param("at", updatedAt)
                .update();
    }

    /** Creates the row when the tool has never swept, so the decision is never lost to a race with the sweep. */
    public void markPendingPin(String projectId, String toolKey, @Nullable String by, String at, String epoch) {
        jdbc.sql(sql("""
                        INSERT INTO {table} (project_id, {key}, state_epoch, updated_at,
                                                      pending_pin_by, pending_pin_at)
                        VALUES (:pid, :tool, :epoch, :at, :by, :at)
                        ON CONFLICT (project_id, {key}) DO UPDATE SET
                            pending_pin_by = EXCLUDED.pending_pin_by,
                            pending_pin_at = EXCLUDED.pending_pin_at,
                            updated_at = EXCLUDED.updated_at
                        """))
                .param("pid", projectId)
                .param("tool", toolKey)
                .param("epoch", epoch)
                .param("by", by)
                .param("at", at)
                .update();
    }

    public void clearPendingPin(String projectId, String toolKey, String at) {
        jdbc.sql(sql("""
                        UPDATE {table}
                           SET pending_pin_by = NULL, pending_pin_at = NULL, updated_at = :at
                         WHERE project_id = :pid AND {key} = :tool
                        """))
                .param("pid", projectId)
                .param("tool", toolKey)
                .param("at", at)
                .update();
    }

    /**
     * Keeps the watermark and reference: replaying history the human just ruled on would rebuild the spell they closed.
     * @param note required by the database; it is what someone wants weeks later when the tool alarms again
     */
    public void reset(String projectId, String toolKey, @Nullable String resetBy, String note, String resetAt) {
        jdbc.sql(sql("""
                        UPDATE {table}
                           SET s_up = 0, s_down = 0,
                               onset_up_at = NULL, onset_down_at = NULL,
                               calls_since_onset_up = 0, calls_since_onset_down = 0,
                               reset_at = :at, reset_by = :by, reset_note = :note, updated_at = :at
                         WHERE project_id = :pid AND {key} = :tool
                        """))
                .param("pid", projectId)
                .param("tool", toolKey)
                .param("by", resetBy)
                .param("note", note)
                .param("at", resetAt)
                .update();
    }

    /**
     * For a ruling that means "the old normal was wrong". The watermark is cleared too, since a row with no reference
     * cannot resume, and so is the learning fence: the reset fence now says where learning starts. @param note
     * required, as for {@link #reset}
     */
    public void resetAndRelearn(String projectId, String key, @Nullable String resetBy, String note, String resetAt) {
        jdbc.sql(sql("""
                        UPDATE {table}
                           SET s_up = 0, s_down = 0,
                               onset_up_at = NULL, onset_down_at = NULL,
                               calls_since_onset_up = 0, calls_since_onset_down = 0,
                               baseline_calls = NULL, baseline_failures = NULL,
                               watermark_bucket = NULL, learned_through = NULL, learn_before = NULL,
                               reset_at = :at, reset_by = :by, reset_note = :note, updated_at = :at
                         WHERE project_id = :pid AND {key} = :key
                        """))
                .param("pid", projectId)
                .param("key", key)
                .param("by", resetBy)
                .param("note", note)
                .param("at", resetAt)
                .update();
    }

    /**
     * Relearns {@code stale} under {@code epoch}, stamped so the next pass does not reset it again while it learns. A
     * row with no reference learned nothing under the old tuning, so it only takes the new epoch: no new fence is cut
     * and its old fences stay, which lets a row the upgrade cleared re-learn its settled history.
     */
    public static CarriedState retune(
            ToolErrorStateRepository states, String projectId, CarriedState stale, String epoch, String now) {
        if (stale.baseline() == null) {
            CarriedState fresh = new CarriedState(
                    stale.toolKey(),
                    State.EMPTY,
                    null,
                    null,
                    epoch,
                    stale.pendingPinBy(),
                    stale.pendingPinAt(),
                    stale.resetAt(),
                    null,
                    stale.learnBefore());
            states.save(projectId, fresh, now);
            return fresh;
        }
        states.resetAndRelearn(projectId, stale.toolKey(), null, TUNING_CHANGED, now);
        CarriedState fresh = new CarriedState(
                stale.toolKey(), State.EMPTY, null, null, epoch, stale.pendingPinBy(), stale.pendingPinAt(), now);
        states.save(projectId, fresh, now);
        return fresh;
    }
}
