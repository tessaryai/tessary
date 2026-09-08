// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.catalog;

import ai.tessary.evals.classifier.ClassifierService;
import ai.tessary.evals.config.TraceMdcBridge;
import ai.tessary.evals.open.obs.LogContext;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.ProjectRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every active project's built-in classifier catalog matching what its org is entitled to —
 * {@link ClassifierService#resyncBuiltIns} on a heartbeat, over {@code ProjectRepository#findActive()}.
 * The second of catalog provisioning's two triggers; {@link ClassifierSeedListener} is the first and
 * fires once, at project creation, against the capabilities of that moment.
 *
 * <h2>Why this is its own worker</h2>
 *
 * This ran inside {@code ClassifierWorker}'s sweep heartbeat, in its loop over {@code
 * substrate.projectsWithObservations()} — {@code SELECT DISTINCT project_id FROM span}. That made
 * catalog provisioning a silent function of trace ingestion. A project with zero spans was not in the
 * scan at all, so switching a classifier's capability flag ON for it changed nothing: the org kept the
 * platform-default three built-ins until its first production span landed, at which point the missing
 * classifiers appeared within a heartbeat with no further action, which reads like a flag that took a
 * day to propagate rather than a scope bug. Which classifiers an org has is a licensing answer owned by
 * the flag layer; it must not wait on telemetry.
 *
 * <p>The scan therefore moved to the project table, and moved OFF the sweep tick rather than merely
 * widening it. Two reasons. The sweep tick's job is claiming and dispatching due jobs, and a scan that
 * now grows with total projects rather than with active ones must not sit in front of that. And the two
 * want different cadences as a deployment grows: {@code evals.classifier.catalog-resync-ms} can be
 * dialled back to minutes without slowing detection by a single tick, because nothing downstream waits
 * on a reconcile — the worst case of a slower cadence is that a flag flipped seconds ago takes one
 * interval to reach an idle project.
 *
 * <h2>What a pass costs</h2>
 *
 * Per project: one {@code classifier} row read and at most one {@code org_feature_flag} read (cached per
 * org for ten seconds, so usually none), and zero writes in the steady state — the catalog is
 * already correct, so every comparison matches and nothing is issued. Reconciling a project used to
 * cost a {@code findByKey} per catalog module on top of that; {@code seedBuiltIns} now indexes one row
 * read instead, which is what makes "every project" affordable where "every project with traffic" was.
 *
 * <p>Sweep ENQUEUE deliberately did not move and still keys on projects with observations: a job row
 * per enabled classifier on a project that has never sent a span would be claimed, dispatched, and
 * sweep an empty window every tick forever, which is exactly the load this split exists to avoid. A
 * project therefore gets its catalog on this cadence and its first sweep on the tick after its first
 * span, which is the correct ordering — there is nothing to sweep before then.
 */
@Component
public class ClassifierCatalogWorker {

    private static final Logger log = LoggerFactory.getLogger(ClassifierCatalogWorker.class);

    private final ClassifierService classifiers;
    private final ProjectRepository projects;
    private final TraceMdcBridge traceBridge;

    public ClassifierCatalogWorker(
            ClassifierService classifiers, ProjectRepository projects, TraceMdcBridge traceBridge) {
        this.classifiers = classifiers;
        this.projects = projects;
        this.traceBridge = traceBridge;
    }

    @Scheduled(fixedDelayString = "${evals.classifier.catalog-resync-ms:60000}")
    public void tick() {
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            tickInner();
        }
    }

    private void tickInner() {
        Instant start = Instant.now();
        List<Project> active;
        try {
            active = projects.findActive();
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "classifier.catalog.project-scan-failed")
                    .field("error", e.getMessage())
                    .log();
            return;
        }

        int seeded = 0;
        int failed = 0;
        for (Project project : active) {
            try {
                seeded += classifiers.resyncBuiltIns(project);
            } catch (RuntimeException e) {
                // One project's catalog failing must not strand every other project's. Nothing downstream
                // waits on a reconcile, so the next tick simply tries again — but a project that fails
                // every tick is invisible without this line, and its symptom (a classifier the org paid
                // for never appearing) looks like a flag problem rather than a backend one.
                failed++;
                StructuredLog.warn(log, Markers.OPS, "classifier.catalog.resync-failed")
                        .field("project", project.id())
                        .field("org", project.orgId())
                        .field("error", e.getMessage())
                        .log();
            }
        }

        // The steady state is "scanned N, changed nothing", every minute, forever — DEBUG, per the
        // logging policy that cost `signal.sweep.empty` its INFO. A pass that actually provisioned
        // something, or failed on a project, is the outcome an operator wants at INFO. Both carry
        // `projects` and `durationMs` because this scan's cost grows with the project table.
        StructuredLog.Builder line = seeded > 0 || failed > 0
                ? StructuredLog.info(log, Markers.OPS, "classifier.catalog.reconciled")
                : StructuredLog.debug(log, "classifier.catalog.reconciled");
        line.message("reconciled the built-in catalog across %d project(s), %d seeded", active.size(), seeded)
                .field("projects", active.size())
                .field("seeded", seeded)
                .field("failed", failed)
                .durationMs(start)
                .log();
    }
}
