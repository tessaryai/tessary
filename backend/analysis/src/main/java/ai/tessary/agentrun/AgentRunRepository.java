// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Triage and RCA agent runs and their steps in plain words. A subject keeps its newest run and the one before it; a run
 * stores at most {@link #MAX_STEPS} steps and keeps counting past them.
 */
@Repository
public class AgentRunRepository {

    static final int MAX_STEPS = 400;

    static final int MAX_WORDS = 200;

    /** The run's newest stored step: its words and when it last moved, joined as {@code l}. */
    private static final String LAST_STEP = """
             LEFT JOIN LATERAL (SELECT s.words AS last_step, COALESCE(s.ended_ms, s.started_ms) AS last_step_ms
                                  FROM agent_run_step s WHERE s.run_id = r.id
                                 ORDER BY s.seq DESC LIMIT 1) l ON TRUE
            """;

    private static final String SUMMARY = """
            SELECT r.id, r.lane, r.subject_id, r.attempt, r.started_at, r.ended_at, r.outcome, r.stop_code,
                   r.step_count, r.session_id, r.note, r.note_ms, l.last_step, l.last_step_ms,
                   (SELECT s.doing FROM agent_run_step s
                     WHERE s.run_id = r.id AND s.ended_ms IS NULL
                     ORDER BY s.seq DESC LIMIT 1) AS doing
              FROM agent_run r
            """ + LAST_STEP;

    private final JdbcClient jdbc;
    private final Clock clock;

    @Autowired
    public AgentRunRepository(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    AgentRunRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public enum Lane {
        TRIAGE,
        RCA;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Lane of(String wire) {
            return valueOf(wire.toUpperCase(Locale.ROOT));
        }
    }

    public enum Outcome {
        DONE,
        RETRYING,
        HELD,
        DEAD,
        FAILED,
        RELEASED,
        ABANDONED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Outcome of(String wire) {
            return valueOf(wire.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * @param doing the words of the newest step that has not ended, null when none is running @param lastStep the
     * words of the newest stored step @param lastStepMs when that step ended, or started while it runs, from
     * {@code startedAt} @param note the agent's newest words between tool calls @param noteMs when it said them, from
     * {@code startedAt}
     */
    public record RunSummary(
            String id,
            Lane lane,
            String subjectId,
            int attempt,
            String startedAt,
            @Nullable String endedAt,
            @Nullable Outcome outcome,
            @Nullable String stopCode,
            int stepCount,
            @Nullable String doing,
            @Nullable String lastStep,
            @Nullable Long lastStepMs,
            @Nullable String note,
            @Nullable Long noteMs,
            @Nullable String sessionId) {}

    /** @param startedMs from the run's {@code started_at} */
    public record StepRow(
            int seq,
            String words,
            String doing,
            long startedMs,
            @Nullable Long endedMs,
            @Nullable Boolean ok) {}

    /** @param stepCount every step the run took, stored or not */
    public record RunSteps(String runId, int stepCount, List<StepRow> steps) {}

    /**
     * Prunes the subject's runs down to its newest one, then opens a new run, in one statement. The statement reads
     * before it writes, so a null {@code attempt} counts on from the job's earlier runs, the pruned ones included.
     *
     * @param attempt null for a job whose row carries no attempt count (RCA)
     */
    public String open(String projectId, Lane lane, String subjectId, String jobId, @Nullable Integer attempt) {
        String id = Ids.ulid();
        jdbc.sql("""
                WITH pruned AS (
                    DELETE FROM agent_run
                     WHERE project_id = :pid AND lane = :lane AND subject_id = :sid
                       AND id <> COALESCE((SELECT k.id FROM agent_run k
                                            WHERE k.project_id = :pid AND k.lane = :lane AND k.subject_id = :sid
                                            ORDER BY k.started_at DESC, k.id DESC LIMIT 1), '')
                )
                INSERT INTO agent_run (id, project_id, lane, subject_id, job_id, attempt, started_at, step_count)
                VALUES (:id, :pid, :lane, :sid, :job,
                        COALESCE(CAST(:attempt AS integer),
                                 (SELECT COALESCE(max(j.attempt), 0) + 1 FROM agent_run j WHERE j.job_id = :job)),
                        :now, 0)
                """)
                .param("id", id)
                .param("pid", projectId)
                .param("lane", lane.wire())
                .param("sid", subjectId)
                .param("job", jobId)
                .param("attempt", attempt)
                .param("now", clock.instant().toString())
                .update();
        return id;
    }

    public void attachSession(String runId, String sessionId) {
        jdbc.sql("UPDATE agent_run SET session_id = :session WHERE id = :id")
                .param("session", sessionId)
                .param("id", runId)
                .update();
    }

    /** The step's seq is the run's new step count; past {@link #MAX_STEPS} only the count moves. */
    public void stepStarted(String runId, String callId, String words, String doing, long startedMs) {
        jdbc.sql("""
                WITH counted AS (
                    UPDATE agent_run SET step_count = step_count + 1 WHERE id = :run RETURNING step_count
                )
                INSERT INTO agent_run_step (run_id, seq, call_id, words, doing, started_ms)
                SELECT :run, step_count, :call, :words, :doing, :startedMs FROM counted WHERE step_count <= :max
                """)
                .param("run", runId)
                .param("call", callId)
                .param("words", cut(words))
                .param("doing", cut(doing))
                .param("startedMs", startedMs)
                .param("max", MAX_STEPS)
                .update();
    }

    /** @param words the done words, or "Could not ..." for a call that failed */
    public void stepEnded(String runId, String callId, long endedMs, boolean ok, String words) {
        jdbc.sql("""
                UPDATE agent_run_step SET ended_ms = :endedMs, ok = :ok, words = :words
                 WHERE run_id = :run AND call_id = :call AND ended_ms IS NULL
                """)
                .param("endedMs", endedMs)
                .param("ok", ok)
                .param("words", cut(words))
                .param("run", runId)
                .param("call", callId)
                .update();
    }

    /** @param atMs from the run's {@code started_at} */
    public void note(String runId, String words, long atMs) {
        jdbc.sql("UPDATE agent_run SET note = :note, note_ms = :atMs WHERE id = :id")
                .param("note", cut(words))
                .param("atMs", atMs)
                .param("id", runId)
                .update();
    }

    public void close(String runId, Outcome outcome, @Nullable String stopCode) {
        jdbc.sql("UPDATE agent_run SET ended_at = :now, outcome = :outcome, stop_code = :code WHERE id = :id")
                .param("now", clock.instant().toString())
                .param("outcome", outcome.wire())
                .param("code", stopCode)
                .param("id", runId)
                .update();
    }

    public Optional<RunSummary> openRunOfJob(String jobId) {
        return openRunsOfJob(jobId).stream().reduce((older, newer) -> newer);
    }

    /** Oldest first. */
    public List<RunSummary> openRunsOfJob(String jobId) {
        return jdbc.sql(SUMMARY + " WHERE r.job_id = :job AND r.ended_at IS NULL ORDER BY r.started_at, r.id")
                .param("job", jobId)
                .query((rs, n) -> summary(rs))
                .list();
    }

    /** One query for a page of subjects; a subject with no run is absent. */
    public Map<String, RunSummary> latestBySubjects(String projectId, Lane lane, Collection<String> subjectIds) {
        if (subjectIds.isEmpty()) return Map.of();
        Map<String, RunSummary> out = new LinkedHashMap<>();
        jdbc.sql("SELECT DISTINCT ON (q.subject_id) q.* FROM (" + SUMMARY
                        + " WHERE r.project_id = :pid AND r.lane = :lane AND r.subject_id IN (:ids)) q"
                        + " ORDER BY q.subject_id, q.started_at DESC, q.id DESC")
                .param("pid", projectId)
                .param("lane", lane.wire())
                .param("ids", subjectIds)
                .query((rs, n) -> summary(rs))
                .list()
                .forEach(r -> out.put(r.subjectId(), r));
        return out;
    }

    /** Steps with a seq above {@code afterSeq}, oldest first; a run outside the project shows none. */
    public RunSteps steps(String projectId, String runId, int afterSeq) {
        int stepCount = jdbc.sql("SELECT step_count FROM agent_run WHERE id = :id AND project_id = :pid")
                .param("id", runId)
                .param("pid", projectId)
                .query(Integer.class)
                .optional()
                .orElse(0);
        List<StepRow> steps = jdbc.sql("""
                SELECT s.seq, s.words, s.doing, s.started_ms, s.ended_ms, s.ok
                  FROM agent_run_step s JOIN agent_run r ON r.id = s.run_id
                 WHERE s.run_id = :id AND r.project_id = :pid AND s.seq > :after
                 ORDER BY s.seq
                """)
                .param("id", runId)
                .param("pid", projectId)
                .param("after", afterSeq)
                .query((rs, n) -> new StepRow(
                        rs.getInt("seq"),
                        rs.getString("words"),
                        rs.getString("doing"),
                        rs.getLong("started_ms"),
                        rs.getObject("ended_ms", Long.class),
                        rs.getObject("ok", Boolean.class)))
                .list();
        return new RunSteps(runId, stepCount, steps);
    }

    private static RunSummary summary(ResultSet rs) throws SQLException {
        String outcome = rs.getString("outcome");
        return new RunSummary(
                rs.getString("id"),
                Lane.of(rs.getString("lane")),
                rs.getString("subject_id"),
                rs.getInt("attempt"),
                rs.getString("started_at"),
                rs.getString("ended_at"),
                outcome == null ? null : Outcome.of(outcome),
                rs.getString("stop_code"),
                rs.getInt("step_count"),
                rs.getString("doing"),
                rs.getString("last_step"),
                rs.getObject("last_step_ms", Long.class),
                rs.getString("note"),
                rs.getObject("note_ms", Long.class),
                rs.getString("session_id"));
    }

    /** Never splits a surrogate pair. */
    static String cut(String words) {
        if (words.length() <= MAX_WORDS) return words;
        int end = Character.isHighSurrogate(words.charAt(MAX_WORDS - 1)) ? MAX_WORDS - 1 : MAX_WORDS;
        return words.substring(0, end);
    }
}
