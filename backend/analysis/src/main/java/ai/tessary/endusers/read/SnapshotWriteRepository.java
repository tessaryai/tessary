// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.LimitLine;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StateResult.TrustNumbers;
import ai.tessary.endusers.judge.model.StopOdds.CalibrationBlock;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import ai.tessary.endusers.model.Verdict;
import ai.tessary.endusers.model.WireCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The judge run's write of the read model: the snapshot of the day, and in place of the last run's rows the issues,
 * their hit user IDs, the user IDs' states and the shared-silence events. Codes are written in their wire form and
 * jsonb as the records the readers read, so a key matches by construction. The caller holds one transaction around
 * {@link #write}: a reader sees the last run or this one, never a mix.
 */
@Repository
public class SnapshotWriteRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper;

    public SnapshotWriteRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.named = named;
        this.mapper = mapper;
    }

    /** One {@code end_user_snapshot} row. Null means not computed. */
    public record Snapshot(
            int day,
            String logicVersion,
            PageState pageState,
            @Nullable PageStateReason reason,
            Map<String, Double> reasonNumbers,
            List<Banner> banners,
            List<LimitLine> limits,
            @Nullable TrustNumbers trust,
            @Nullable StageCounts stages,
            @Nullable Map<SetAsideReason, Integer> setAside,
            @Nullable StopStageState stopStage,
            @Nullable Map<String, Double> stopStageNumbers,
            @Nullable List<CalibrationBlock> calibration,
            @Nullable Integer h,
            @Nullable Integer f) {}

    /** One {@code end_user_issue} row; its day is the snapshot's. */
    public record Issue(
            String lineageKey,
            @Nullable String caseId,
            List<String> findingIds,
            @Nullable Integer rank,
            int usersHit,
            @Nullable Integer compared,
            @Nullable Double lost,
            @Nullable Double low,
            @Nullable Double high,
            Verdict verdict,
            boolean shown,
            @Nullable String groupId,
            @Nullable List<CurvePoint> curve,
            IssueDiagnostics diagnostics) {}

    /** @param inEstimate false for a late hit and for a hit user ID the estimate cannot compare */
    public record IssueHit(String lineageKey, byte[] userKey, int firstHitDay, boolean inEstimate) {}

    /** The judge columns of one {@code end_user} row. */
    public record UserState(
            byte[] userKey,
            UserIdState state,
            @Nullable Substate substate,
            @Nullable Grade grade,
            @Nullable Double pStop,
            @Nullable Double pNominal,
            int daysSilent,
            @Nullable SetAsideReason setAside,
            @Nullable Stage stage) {}

    /** @param scope {@code app}, {@code shape:<group>} or {@code cluster:<number>} */
    public record SilenceEvent(
            String scope,
            int startDay,
            @Nullable Integer endDay,
            SilenceState state,
            int members,
            double i,
            double i0,
            double rho) {}

    /** Everything one judge run writes. */
    public record Run(
            Snapshot snapshot,
            List<Issue> issues,
            List<IssueHit> issueHits,
            List<UserState> states,
            List<SilenceEvent> silence) {}

    /** @return the rows of this run written */
    public long write(String projectId, Run run) {
        Snapshot s = run.snapshot();
        jdbc.sql("""
                INSERT INTO end_user_snapshot (project_id, day, logic_version, page_state, page_state_reason,
                                               reason_numbers, banners, limits, trust, stage_counts, set_aside,
                                               stop_stage_state, stop_stage_numbers, calibration, h, f)
                VALUES (:pid, :day, :version, :state, :reason, CAST(:numbers AS jsonb), CAST(:banners AS jsonb),
                        CAST(:limits AS jsonb), CAST(:trust AS jsonb), CAST(:stages AS jsonb), CAST(:setAside AS jsonb),
                        :stopStage, CAST(:stopNumbers AS jsonb), CAST(:calibration AS jsonb), :h, :f)
                ON CONFLICT (project_id, day) DO UPDATE SET
                    logic_version = EXCLUDED.logic_version, page_state = EXCLUDED.page_state,
                    page_state_reason = EXCLUDED.page_state_reason, reason_numbers = EXCLUDED.reason_numbers,
                    banners = EXCLUDED.banners, limits = EXCLUDED.limits, trust = EXCLUDED.trust,
                    stage_counts = EXCLUDED.stage_counts, set_aside = EXCLUDED.set_aside,
                    stop_stage_state = EXCLUDED.stop_stage_state, stop_stage_numbers = EXCLUDED.stop_stage_numbers,
                    calibration = EXCLUDED.calibration, h = EXCLUDED.h, f = EXCLUDED.f, created_at = now()
                """)
                .param("pid", projectId)
                .param("day", s.day())
                .param("version", s.logicVersion())
                .param("state", s.pageState().wire())
                .param("reason", wire(s.reason()))
                .param("numbers", json(s.reasonNumbers()))
                .param("banners", json(s.banners()))
                .param("limits", json(s.limits()))
                .param("trust", json(s.trust()))
                .param("stages", json(s.stages()))
                .param("setAside", json(s.setAside()))
                .param("stopStage", wire(s.stopStage()))
                .param("stopNumbers", json(s.stopStageNumbers()))
                .param("calibration", json(s.calibration()))
                .param("h", s.h())
                .param("f", s.f())
                .update();
        for (String table : List.of("end_user_issue_hit", "end_user_issue", "end_user_silence_event")) {
            jdbc.sql("DELETE FROM " + table + " WHERE project_id = :pid")
                    .param("pid", projectId)
                    .update();
        }
        batch(
                """
                INSERT INTO end_user_issue (project_id, lineage_key, day, case_id, finding_ids, rank, user_ids_hit,
                                            compared, lost, low, high, verdict, shown, group_id, curve, diagnostics)
                VALUES (:pid, :lineage, :day, :case, :findings, :rank, :hit, :compared, :lost, :low, :high, :verdict,
                        :shown, :group, CAST(:curve AS jsonb), CAST(:diagnostics AS jsonb))
                """,
                run.issues().stream()
                        .map(i -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("lineage", i.lineageKey())
                                .addValue("day", s.day())
                                .addValue("case", i.caseId())
                                .addValue("findings", i.findingIds().toArray(String[]::new))
                                .addValue("rank", i.rank())
                                .addValue("hit", i.usersHit())
                                .addValue("compared", i.compared())
                                .addValue("lost", i.lost())
                                .addValue("low", i.low())
                                .addValue("high", i.high())
                                .addValue("verdict", i.verdict().wire())
                                .addValue("shown", i.shown())
                                .addValue("group", i.groupId())
                                .addValue("curve", json(i.curve()))
                                .addValue("diagnostics", json(i.diagnostics())))
                        .toList());
        batch(
                """
                INSERT INTO end_user_issue_hit (project_id, lineage_key, user_key, first_hit_day, in_estimate)
                VALUES (:pid, :lineage, :key, :day, :inEstimate)
                """,
                run.issueHits().stream()
                        .map(h -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("lineage", h.lineageKey())
                                .addValue("key", h.userKey())
                                .addValue("day", h.firstHitDay())
                                .addValue("inEstimate", h.inEstimate()))
                        .toList());
        jdbc.sql("""
                UPDATE end_user
                   SET state = NULL, substate = NULL, grade = NULL, p_stop = NULL, p_nominal = NULL, days_silent = NULL,
                       set_aside_reason = NULL, stage = NULL, updated_at = now()
                 WHERE project_id = :pid AND state IS NOT NULL
                """).param("pid", projectId).update();
        batch(
                """
                UPDATE end_user
                   SET state = :state, substate = :substate, grade = :grade, p_stop = :pStop, p_nominal = :pNominal,
                       days_silent = :silent, set_aside_reason = :setAside, stage = :stage, updated_at = now()
                 WHERE project_id = :pid AND user_key = :key
                """,
                run.states().stream()
                        .map(u -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("key", u.userKey())
                                .addValue("state", u.state().wire())
                                .addValue("substate", wire(u.substate()))
                                .addValue("grade", wire(u.grade()))
                                .addValue("pStop", u.pStop())
                                .addValue("pNominal", u.pNominal())
                                .addValue("silent", u.daysSilent())
                                .addValue("setAside", wire(u.setAside()))
                                .addValue("stage", wire(u.stage())))
                        .toList());
        batch(
                """
                INSERT INTO end_user_silence_event (project_id, scope, start_day, end_day, state, members, i, i0, rho)
                VALUES (:pid, :scope, :start, :end, :state, :members, :i, :i0, :rho)
                """,
                run.silence().stream()
                        .map(e -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("scope", e.scope())
                                .addValue("start", e.startDay())
                                .addValue("end", e.endDay())
                                .addValue("state", e.state().wire())
                                .addValue("members", e.members())
                                .addValue("i", e.i())
                                .addValue("i0", e.i0())
                                .addValue("rho", e.rho()))
                        .toList());
        return 1L
                + run.issues().size()
                + run.issueHits().size()
                + run.states().size()
                + run.silence().size();
    }

    private void batch(String sql, List<? extends SqlParameterSource> rows) {
        if (rows.isEmpty()) return;
        named.batchUpdate(sql, rows.toArray(SqlParameterSource[]::new));
    }

    private static @Nullable String wire(@Nullable WireCode code) {
        return code == null ? null : code.wire();
    }

    private @Nullable String json(@Nullable Object value) {
        return value == null ? null : mapper.valueToTree(value).toString();
    }
}
