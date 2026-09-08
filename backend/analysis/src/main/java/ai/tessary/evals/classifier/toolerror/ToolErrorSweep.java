// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.toolerror;

import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.worker.ClassifierJobRepository;
import ai.tessary.evals.classifier.worker.ClassifierJobRow;
import ai.tessary.evals.classifier.worker.ClassifierSweep;
import ai.tessary.evals.classifier.worker.SweepContext;
import ai.tessary.evals.classifier.worker.SweepOutcome;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The tool-error classifier's own sweep: one scheduled pass that replays the hourly aggregate and
 * writes the findings, on the worker's cadence and nobody else's.
 *
 * <p><b>Why this exists at all.</b> The recompute used to run inside {@code ToolErrorCaseSource} — the
 * case reconciler's read path. That made this the one classifier whose findings only advanced while
 * something was looking at Triage: a read that wrote, a rail on the Classifiers page showing whatever
 * the last case pass happened to leave, and no way to ask a project what its tool-error findings were
 * without changing them. The work is unchanged; where it is driven from is not.
 *
 * <p><b>No cursor, deliberately.</b> Every other sweep pages over substrate and advances
 * {@code (cursor_at, cursor_id)} so it never re-reads a trace. This one re-derives every number from an
 * hourly aggregate over a fixed replay window on every pass ({@link ToolErrorService#REPLAY_WINDOW}), so
 * there is no position to remember and the job is marked swept with a null cursor. Idempotence comes
 * from the recompute itself — counts are assigned rather than added — not from a watermark.
 */
@Component
public class ToolErrorSweep implements ClassifierSweep {

    private final ClassifierJobRepository jobs;
    private final ToolErrorService service;

    public ToolErrorSweep(ClassifierJobRepository jobs, ToolErrorService service) {
        this.jobs = jobs;
        this.service = service;
    }

    /**
     * Run one tool-error pass for a claimed job.
     *
     * <p>The job is marked swept whatever the recompute found, including when the classifier is disabled
     * or the project has no tool traffic: an unfinished job would be re-claimed forever and burn the
     * dead-letter budget on a classifier that is working exactly as configured.
     */
    @Override
    public SweepOutcome sweep(SweepContext ctx) {
        ClassifierJobRow job = ctx.job();
        int spells = service.refresh(job.projectId());
        jobs.markSwept(job.id(), null, null);
        // `scanned` is 0 and `fired` counts TOOLS IN A SPELL, never failing calls: this sweep reads an
        // hourly aggregate rather than a page of rows, so there is no unit it can honestly report having
        // scanned.
        return new SweepOutcome(0, spells);
    }

    @Override
    public Set<String> kinds() {
        return Set.of(BuiltInDetector.Kind.TOOL_ERROR);
    }
}
