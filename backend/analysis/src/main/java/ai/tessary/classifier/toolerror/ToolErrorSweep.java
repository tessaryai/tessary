// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.classifier.worker.ClassifierSweep;
import ai.tessary.classifier.worker.SweepContext;
import ai.tessary.classifier.worker.SweepOutcome;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The tool-error classifier's own sweep, so findings advance on the worker's cadence rather than when someone reads
 * Triage. No cursor: every pass re-derives from the hourly aggregate over {@link ToolErrorService#REPLAY_WINDOW}, and
 * idempotence comes from assigning counts rather than adding them.
 */
@Component
public class ToolErrorSweep implements ClassifierSweep {

    private final ClassifierJobRepository jobs;
    private final ToolErrorService service;

    public ToolErrorSweep(ClassifierJobRepository jobs, ToolErrorService service) {
        this.jobs = jobs;
        this.service = service;
    }

    /** Marked swept whatever it found, or the job would be re-claimed forever and burn the dead-letter budget. */
    @Override
    public SweepOutcome sweep(SweepContext ctx) {
        ClassifierJobRow job = ctx.job();
        int spells = service.refresh(job.projectId());
        jobs.markSwept(job.id(), null, null);
        // `scanned` is 0 and `fired` counts tools in a spell: an aggregate has no unit to report as scanned.
        return new SweepOutcome(0, spells);
    }

    @Override
    public Set<String> kinds() {
        return Set.of(BuiltInDetector.Kind.TOOL_ERROR);
    }
}
