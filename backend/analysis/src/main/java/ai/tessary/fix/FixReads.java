// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentRunDtos.RunStepsView;
import ai.tessary.agentrun.AgentRunRepository;
import ai.tessary.agentrun.AgentRunRepository.RunSummary;
import ai.tessary.fix.FixDtos.FixCheckView;
import ai.tessary.fix.FixDtos.FixPrView;
import ai.tessary.fix.FixDtos.FixRunView;
import ai.tessary.fix.FixDtos.FixView;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RcaRunView;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** What the case page reads about a case's fix: its newest pull request, its newest run and that run's steps. */
@Service
public class FixReads {

    private final FixRunRepository fixRuns;
    private final FixPrRepository prs;
    private final AgentRunRepository runs;

    public FixReads(FixRunRepository fixRuns, FixPrRepository prs, AgentRunRepository runs) {
        this.fixRuns = fixRuns;
        this.prs = prs;
        this.runs = runs;
    }

    /** Null for a case no fix step has looked at. */
    public @Nullable FixView forCase(String projectId, String caseId) {
        List<FixPrRow> casePrs = prs.listByCase(projectId, caseId);
        Optional<FixRunRow> latest = fixRuns.latestByCase(projectId, caseId);
        if (casePrs.isEmpty() && latest.isEmpty()) return null;
        FixPrView pr = casePrs.isEmpty() ? null : FixPrView.of(casePrs.get(0));
        return new FixView(pr, latest.map(run -> view(projectId, run, casePrs)).orElse(null));
    }

    private FixRunView view(String projectId, FixRunRow run, List<FixPrRow> casePrs) {
        String prUrl = casePrs.stream()
                .filter(p -> p.id().equals(run.fixPrId()))
                .map(FixPrRow::prUrl)
                .findFirst()
                .orElse(null);
        RunSummary summary = runs.latestBySubjects(projectId, AgentRunRepository.Lane.FIX, List.of(run.id()))
                .get(run.id());
        return new FixRunView(
                run.id(),
                run.status(),
                run.outcome(),
                run.reason(),
                run.note(),
                prUrl,
                run.causes(),
                run.checks().stream().map(FixCheckView::of).toList(),
                run.patchFiles(),
                run.createdAt(),
                run.updatedAt(),
                summary == null ? null : runBar(run, summary));
    }

    /** The run bar the RCA run uses, so the page draws both the same way. A failed run reads stopped. */
    static RcaRunView runBar(FixRunRow run, RunSummary s) {
        String state =
                switch (run.status()) {
                    case FixRunRow.Status.QUEUED -> RcaRunView.QUEUED;
                    case FixRunRow.Status.RUNNING -> RcaRunView.RUNNING;
                    case FixRunRow.Status.DONE -> RcaRunView.DONE;
                    default -> RcaRunView.STOPPED;
                };
        boolean running = RcaRunView.RUNNING.equals(state);
        return new RcaRunView(
                state,
                run.createdAt(),
                s.id(),
                s.startedAt(),
                s.endedAt(),
                s.stepCount(),
                running ? s.doing() : null,
                running ? s.lastStep() : null,
                running ? s.lastStepMs() : null,
                running ? s.note() : null,
                running ? s.noteMs() : null,
                s.stopCode());
    }

    /** The run's newest agent run's steps after step {@code afterSeq}; a run outside the project is not found. */
    public RunStepsView steps(String projectId, String fixRunId, int afterSeq) {
        if (afterSeq < 0) throw new TessaryException(CommonError.VALIDATION_FAILED, "after must be zero or more");
        RunSummary run = fixRuns.findById(projectId, fixRunId)
                .map(r -> runs.latestBySubjects(projectId, AgentRunRepository.Lane.FIX, List.of(fixRunId))
                        .get(fixRunId))
                .orElse(null);
        if (run == null) throw new TessaryException(CommonError.NOT_FOUND, fixRunId);
        return RunStepsView.of(runs.steps(projectId, run.id(), afterSeq));
    }
}
