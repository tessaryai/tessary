// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.agentrun.AgentRunRepository.Lane;
import ai.tessary.agentrun.AgentRunRepository.Outcome;
import ai.tessary.agentrun.AgentRunRepository.RunSteps;
import ai.tessary.agentrun.AgentRunRepository.RunSummary;
import ai.tessary.agentrun.AgentRunRepository.StepRow;
import ai.tessary.retention.ProjectPurgeRepository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The run store against the real {@code agent_run} and {@code agent_run_step} tables. A run and its steps are what the
 * finding and case pages show while an agent works, so the bugs here are a column read back wrong, a store that grows
 * without bound, and one project's steps shown to another.
 */
@SpringBootTest
record AgentRunRepositoryTest(TenantService tenants, JdbcClient jdbc, ProjectPurgeRepository purge) {

    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

    private AgentRunRepository at(Instant now) {
        return new AgentRunRepository(jdbc, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String project(String name) {
        return TenantFixture.bootstrap(tenants, name).project().id();
    }

    /** Repository round trip: every column of a run and of its steps reads back as written. */
    @Test
    void aRunWrittenWithEveryColumnReadsBackWhole() {
        String pid = project("agent-run-round-trip");
        String runId = at(T0).open(pid, Lane.RCA, "report-1", pid + "/job-1", 3);
        AgentRunRepository runs = at(T0.plusSeconds(90));
        runs.attachSession(runId, "s-1");
        runs.stepStarted(runId, "c-1", "Read the finding and its numbers", "Reading the finding and its numbers", 4);
        runs.stepStarted(runId, "c-2", "Read commit a41c0de", "Reading commit a41c0de", 2_500);
        runs.stepEnded(runId, "c-1", 1_200, true, "Read the finding and its numbers");

        assertEquals(
                Optional.of(new RunSummary(
                        runId,
                        Lane.RCA,
                        "report-1",
                        3,
                        T0.toString(),
                        null,
                        null,
                        null,
                        2,
                        "Reading commit a41c0de",
                        "s-1")),
                runs.openRunOfJob(pid + "/job-1"));

        runs.stepEnded(runId, "c-2", 3_000, false, "Could not read commit a41c0de");
        runs.close(runId, Outcome.FAILED, "RCA.PROVIDER_REJECTED");

        assertEquals(
                Map.of(
                        "report-1",
                        new RunSummary(
                                runId,
                                Lane.RCA,
                                "report-1",
                                3,
                                T0.toString(),
                                T0.plusSeconds(90).toString(),
                                Outcome.FAILED,
                                "RCA.PROVIDER_REJECTED",
                                2,
                                null,
                                "s-1")),
                runs.latestBySubjects(pid, Lane.RCA, List.of("report-1")));
        assertEquals(
                new RunSteps(
                        runId,
                        2,
                        List.of(
                                new StepRow(
                                        1,
                                        "Read the finding and its numbers",
                                        "Reading the finding and its numbers",
                                        4,
                                        1_200L,
                                        true),
                                new StepRow(
                                        2,
                                        "Could not read commit a41c0de",
                                        "Reading commit a41c0de",
                                        2_500,
                                        3_000L,
                                        false))),
                runs.steps(pid, runId, 0));
        assertEquals(Optional.empty(), runs.openRunOfJob(pid + "/job-1"), "a closed run is no longer open");
    }

    /** Unbounded step storage: a subject keeps its newest run and the one before it, never more. */
    @Test
    void openingARunKeepsOnlyTheNewestEarlierRunOfThatSubject() {
        String pid = project("agent-run-prune");
        String first = at(T0).open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 1);
        at(T0).stepStarted(first, "c-1", "Read a file", "Reading a file", 1);
        String second = at(T0.plusSeconds(60)).open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 2);
        String other = at(T0.plusSeconds(61)).open(pid, Lane.TRIAGE, "finding-2", pid + "/job-2", 1);
        String third = at(T0.plusSeconds(120)).open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 3);

        assertEquals(
                List.of(second, third),
                jdbc.sql("SELECT id FROM agent_run WHERE subject_id = 'finding-1' AND project_id = :pid"
                                + " ORDER BY started_at")
                        .param("pid", pid)
                        .query(String.class)
                        .list());
        assertEquals(0, steps(first), "the pruned run's steps go with it");
        assertEquals(
                List.of(other),
                jdbc.sql("SELECT id FROM agent_run WHERE subject_id = 'finding-2' AND project_id = :pid")
                        .param("pid", pid)
                        .query(String.class)
                        .list(),
                "another subject's run is untouched");
    }

    /**
     * An RCA job's row carries no attempt count, so a run opened with none counts on from the job's earlier runs: a
     * reclaimed job reads as its second attempt, not its first again.
     */
    @Test
    void aRunOpenedWithNoAttemptCountsOnFromTheJobsEarlierRuns() {
        String pid = project("agent-run-attempt");
        String first = at(T0).open(pid, Lane.RCA, "report-1", pid + "/job-1", null);
        String second = at(T0.plusSeconds(60)).open(pid, Lane.RCA, "report-1", pid + "/job-1", null);
        String third = at(T0.plusSeconds(120)).open(pid, Lane.RCA, "report-1", pid + "/job-1", null);

        assertEquals(
                List.of(2, 3),
                at(T0).openRunsOfJob(pid + "/job-1").stream()
                        .map(RunSummary::attempt)
                        .toList());
        assertEquals(
                List.of(second, third),
                at(T0).openRunsOfJob(pid + "/job-1").stream()
                        .map(RunSummary::id)
                        .toList());
        assertEquals(
                0,
                jdbc.sql("SELECT count(*) FROM agent_run WHERE id = :id")
                        .param("id", first)
                        .query(Integer.class)
                        .single());
    }

    /** One run filling the table: steps past 400 are counted but not stored, and long words are cut at 200. */
    @Test
    void stepsPast400AreCountedButNotStored() {
        String pid = project("agent-run-cap");
        AgentRunRepository runs = at(T0);
        String runId = runs.open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 1);
        for (int i = 1; i <= 402; i++) {
            runs.stepStarted(runId, "c-" + i, "w".repeat(250), "d".repeat(250), i);
        }
        runs.stepEnded(runId, "c-402", 500, true, "Read a file");

        RunSteps steps = runs.steps(pid, runId, 398);
        assertEquals(402, steps.stepCount());
        assertEquals(List.of(399, 400), steps.steps().stream().map(StepRow::seq).toList());
        assertEquals("w".repeat(200), steps.steps().getFirst().words());
        assertEquals("d".repeat(200), steps.steps().getFirst().doing());
        assertEquals(400, steps(runId));
    }

    /** Cross-project leak: one query answers a page of subjects, and only for the project asked about. */
    @Test
    void latestBySubjectsAnswersAPageOfFindingsAndOnlyForThisProject() {
        String pid = project("agent-run-latest");
        String neighbour = project("agent-run-latest-neighbour");
        at(T0).open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 1);
        String newest = at(T0.plusSeconds(60)).open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 2);
        String second = at(T0).open(pid, Lane.TRIAGE, "finding-2", pid + "/job-2", 1);
        at(T0).open(pid, Lane.RCA, "finding-3", pid + "/job-3", 1);
        at(T0).open(neighbour, Lane.TRIAGE, "finding-4", pid + "/job-4", 1);

        Map<String, RunSummary> latest = at(T0).latestBySubjects(
                        pid, Lane.TRIAGE, List.of("finding-1", "finding-2", "finding-3", "finding-4", "finding-5"));

        assertEquals(Map.of("finding-1", newest, "finding-2", second), ids(latest));
        assertEquals(Map.of(), at(T0).latestBySubjects(pid, Lane.TRIAGE, List.of()));
        assertEquals(
                new RunSteps(newest, 0, List.of()),
                at(T0).steps(neighbour, newest, 0),
                "another project's run shows no steps");
    }

    /** A poll that resends every step: only steps after the given seq come back. */
    @Test
    void stepsAfterASeqReturnOnlyNewerSteps() {
        String pid = project("agent-run-after");
        AgentRunRepository runs = at(T0);
        String runId = runs.open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 1);
        runs.stepStarted(runId, "c-1", "Read a file", "Reading a file", 1);
        runs.stepStarted(runId, "c-2", "Ran checks/a.py", "Running checks/a.py", 2);
        runs.stepStarted(runId, "c-3", "Counted the evidence", "Counting the evidence", 3);

        assertEquals(
                List.of(3),
                runs.steps(pid, runId, 2).steps().stream().map(StepRow::seq).toList());
        assertEquals(List.of(), runs.steps(pid, runId, 3).steps());
    }

    /** Orphans after a project delete: the purge's batches empty a project's runs and steps, and only its own. */
    @Test
    void theProjectPurgeDeletesRunsAndSteps() {
        String pid = project("agent-run-purge");
        String neighbour = project("agent-run-purge-neighbour");
        String doomed = at(T0).open(pid, Lane.TRIAGE, "finding-1", pid + "/job-1", 1);
        at(T0).stepStarted(doomed, "c-1", "Read a file", "Reading a file", 1);
        String kept = at(T0).open(neighbour, Lane.TRIAGE, "finding-1", pid + "/job-2", 1);
        at(T0).stepStarted(kept, "c-1", "Read a file", "Reading a file", 1);

        for (String table : ProjectPurgeRepository.TABLES) {
            while (purge.deleteBatch(table, pid, 1) > 0) {}
        }

        assertEquals(0, steps(doomed));
        assertEquals(List.of(), at(T0).openRunsOfJob(pid + "/job-1"));
        assertEquals(1, steps(kept));
        assertEquals(1, at(T0).openRunsOfJob(pid + "/job-2").size());
    }

    private int steps(String runId) {
        return jdbc.sql("SELECT count(*) FROM agent_run_step WHERE run_id = :id")
                .param("id", runId)
                .query(Integer.class)
                .single();
    }

    private static Map<String, String> ids(Map<String, RunSummary> runs) {
        return runs.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().id()));
    }
}
