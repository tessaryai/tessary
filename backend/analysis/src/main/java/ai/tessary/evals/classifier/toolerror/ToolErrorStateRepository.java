// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.toolerror;

import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.State;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link CarriedState}, one row per tool. Design contract:
 * {@code classifiers/tool_error/PROGRAM.md} §5, which carries the argument for why this classifier has
 * state at all and what guards the bug class that came back with it.
 */
@Repository
public class ToolErrorStateRepository {

    private final JdbcClient jdbc;

    public ToolErrorStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every carried state in the project, keyed by tool. Empty for a project that has never swept. */
    public Map<String, CarriedState> byTool(String projectId) {
        return list(projectId).stream().collect(Collectors.toMap(CarriedState::toolKey, c -> c));
    }

    /** Every carried state in the project, for the map above and for tests that want the order. */
    public List<CarriedState> list(String projectId) {
        return jdbc.sql("""
                        SELECT tool_key, s_up, s_down, onset_up_at, onset_down_at,
                               calls_since_onset_up, calls_since_onset_down,
                               baseline_calls, baseline_failures, watermark_bucket, state_epoch,
                               pending_pin_by, pending_pin_at
                          FROM tool_error_state
                         WHERE project_id = :pid
                         ORDER BY tool_key
                        """)
                .param("pid", projectId)
                .query((rs, n) -> {
                    long baselineCalls = rs.getLong("baseline_calls");
                    ToolErrorRate baseline = null;
                    if (!rs.wasNull()) {
                        baseline = new ToolErrorRate();
                        baseline.addCounts(baselineCalls, rs.getLong("baseline_failures"));
                    }
                    return new CarriedState(
                            rs.getString("tool_key"),
                            new State(
                                    rs.getDouble("s_up"),
                                    rs.getDouble("s_down"),
                                    rs.getString("onset_up_at"),
                                    rs.getString("onset_down_at"),
                                    rs.getLong("calls_since_onset_up"),
                                    rs.getLong("calls_since_onset_down")),
                            baseline,
                            rs.getString("watermark_bucket"),
                            rs.getString("state_epoch"),
                            rs.getString("pending_pin_by"),
                            rs.getString("pending_pin_at"));
                })
                .list();
    }

    /**
     * Write a tool's advanced state.
     *
     * <p>Upsert, and it deliberately leaves {@code reset_*} and {@code pending_pin_*} alone: those record
     * human decisions, and a sweep running seconds later must not erase the note explaining why the
     * accumulator was cleared, nor an absorb that is waiting for enough calls to honour. They are read
     * onto {@link CarriedState} and written only by the methods below.
     */
    public void save(String projectId, CarriedState carried, String updatedAt) {
        State state = carried.state();
        ToolErrorRate baseline = carried.baseline();
        jdbc.sql("""
                        INSERT INTO tool_error_state (
                            project_id, tool_key, s_up, s_down, onset_up_at, onset_down_at,
                            calls_since_onset_up, calls_since_onset_down,
                            baseline_calls, baseline_failures, watermark_bucket, state_epoch, updated_at)
                        VALUES (:pid, :tool, :sUp, :sDown, :onsetUp, :onsetDown, :callsUp, :callsDown,
                                :baseCalls, :baseFailures, :watermark, :epoch, :at)
                        ON CONFLICT (project_id, tool_key) DO UPDATE SET
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
                            updated_at = EXCLUDED.updated_at
                        """)
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
                .param("at", updatedAt)
                .update();
    }

    /**
     * Record an absorb that arrived before the run since onset was thick enough to pin a reference from.
     *
     * <p>Creates the row when the tool has never swept, so a decision is never lost to a race with the
     * sweep that would have created it.
     */
    public void markPendingPin(String projectId, String toolKey, @Nullable String by, String at, String epoch) {
        jdbc.sql("""
                        INSERT INTO tool_error_state (project_id, tool_key, state_epoch, updated_at,
                                                      pending_pin_by, pending_pin_at)
                        VALUES (:pid, :tool, :epoch, :at, :by, :at)
                        ON CONFLICT (project_id, tool_key) DO UPDATE SET
                            pending_pin_by = EXCLUDED.pending_pin_by,
                            pending_pin_at = EXCLUDED.pending_pin_at,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("pid", projectId)
                .param("tool", toolKey)
                .param("epoch", epoch)
                .param("by", by)
                .param("at", at)
                .update();
    }

    /** Drop a pending absorb, because it has been honoured or the tool has moved on. */
    public void clearPendingPin(String projectId, String toolKey, String at) {
        jdbc.sql("""
                        UPDATE tool_error_state
                           SET pending_pin_by = NULL, pending_pin_at = NULL, updated_at = :at
                         WHERE project_id = :pid AND tool_key = :tool
                        """)
                .param("pid", projectId)
                .param("tool", toolKey)
                .param("at", at)
                .update();
    }

    /**
     * Clear a tool's accumulator because a human dispositioned its case.
     *
     * <p><b>The watermark and the reference are kept.</b> Resetting means "start gathering evidence again
     * from here", not "read the last month again" — replaying history the human just ruled on would
     * rebuild the very spell they closed. If the tool is genuinely fixed the accumulator stays at zero;
     * if it is not, it climbs from zero and raises a NEW case with an honest new onset rather than
     * resurrecting the old one off stale evidence.
     *
     * @param note why it was cleared. Required — the database rejects a reset without one, because this
     *     is the sentence somebody wants six weeks later when the same tool alarms again
     */
    public void reset(String projectId, String toolKey, @Nullable String resetBy, String note, String resetAt) {
        jdbc.sql("""
                        UPDATE tool_error_state
                           SET s_up = 0, s_down = 0,
                               onset_up_at = NULL, onset_down_at = NULL,
                               calls_since_onset_up = 0, calls_since_onset_down = 0,
                               reset_at = :at, reset_by = :by, reset_note = :note, updated_at = :at
                         WHERE project_id = :pid AND tool_key = :tool
                        """)
                .param("pid", projectId)
                .param("tool", toolKey)
                .param("by", resetBy)
                .param("note", note)
                .param("at", resetAt)
                .update();
    }
}
