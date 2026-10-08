// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository;
import ai.tessary.classifier.frustration.FrustrationRateRepository;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository.Restart;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's reset of one classifier: forget what it found and learned, and check every trace still kept
 * again from the start.
 *
 * <p>What goes: its detections, its assessments, the rate state and baselines it learned, and its open
 * findings with no ruling. What stays: ruled findings and the cases they back, alert rules, and the cost
 * ledger. A provider-backed classifier pays again for every trace it re-checks, which is why the UI asks
 * twice.
 *
 * <p>The rate state tables carry no {@code classifier_id}; they are cleared by the project, which is
 * correct only because each is owned by one built-in per project.
 */
@Service
public class ClassifierReset {

    private static final Logger log = LoggerFactory.getLogger(ClassifierReset.class);

    private final ClassifierService classifiers;
    private final ClassifierRepository rows;
    private final ClassifierJobRepository jobs;
    private final ClassifierDetectionWriteRepository detections;
    private final FindingRepository findings;
    private final FrustrationAssessmentRepository frustrationAssessments;
    private final FrustrationRateRepository frustrationRates;
    private final GroundednessAssessmentRepository groundednessAssessments;
    private final GroundednessRateRepository groundednessRates;
    private final MalformedOutputRateRepository malformedOutputRates;
    private final ToolErrorStateRepository toolErrorStates;
    private final ToolErrorReferenceRepository toolErrorReferences;
    private final MetricBaselineRepository metricBaselines;

    public ClassifierReset(
            ClassifierService classifiers,
            ClassifierRepository rows,
            ClassifierJobRepository jobs,
            ClassifierDetectionWriteRepository detections,
            FindingRepository findings,
            FrustrationAssessmentRepository frustrationAssessments,
            FrustrationRateRepository frustrationRates,
            GroundednessAssessmentRepository groundednessAssessments,
            GroundednessRateRepository groundednessRates,
            MalformedOutputRateRepository malformedOutputRates,
            ToolErrorStateRepository toolErrorStates,
            ToolErrorReferenceRepository toolErrorReferences,
            MetricBaselineRepository metricBaselines) {
        this.classifiers = classifiers;
        this.rows = rows;
        this.jobs = jobs;
        this.detections = detections;
        this.findings = findings;
        this.frustrationAssessments = frustrationAssessments;
        this.frustrationRates = frustrationRates;
        this.groundednessAssessments = groundednessAssessments;
        this.groundednessRates = groundednessRates;
        this.malformedOutputRates = malformedOutputRates;
        this.toolErrorStates = toolErrorStates;
        this.toolErrorReferences = toolErrorReferences;
        this.metricBaselines = metricBaselines;
    }

    /**
     * Reset the classifier {@code id}. The job restart comes first: it locks the sweep job until this
     * transaction commits, so no sweep can write into the tables while they are cleared.
     *
     * @throws TessaryException {@link ClassifierError#SWEEP_RUNNING} when a worker holds the sweep now
     */
    @Transactional
    public ClassifierRow reset(String projectId, String id, @Nullable String actor) {
        ClassifierRow row = classifiers.get(projectId, id);
        Restart restart = jobs.restart(projectId, row.id());
        if (restart == Restart.SWEEP_RUNNING) {
            StructuredLog.info(log, Markers.OPS, "classifier.reset.refused")
                    .message("reset refused: a sweep holds the job")
                    .field("project", projectId)
                    .field("classifier", row.classifierKey())
                    .field("actor", actor)
                    .log();
            throw new TessaryException(ClassifierError.SWEEP_RUNNING, row.name());
        }

        int detectionsDeleted = detections.deleteByClassifier(row.detector(), projectId, row.id());
        int assessmentsDeleted = 0;
        int learnedStateDeleted =
                switch (row.detector()) {
                    case Kind.FRUSTRATION -> {
                        assessmentsDeleted = frustrationAssessments.deleteByClassifier(projectId, row.id());
                        yield frustrationRates.states().deleteAll(projectId);
                    }
                    case Kind.GROUNDEDNESS -> {
                        assessmentsDeleted = groundednessAssessments.deleteByClassifier(projectId, row.id());
                        yield groundednessRates.states().deleteAll(projectId);
                    }
                    case Kind.MALFORMED_OUTPUT -> malformedOutputRates.states().deleteAll(projectId);
                    case Kind.TOOL_ERROR ->
                        toolErrorStates.deleteAll(projectId) + toolErrorReferences.deleteAll(projectId);
                    case Kind.COST_DRIFT, Kind.DURATION_DRIFT ->
                        metricBaselines.deleteByClassifier(projectId, row.id());
                    default -> 0;
                };
        int findingsClosed = findings.closeUnruled(
                projectId, row.classifierKey(), Instant.now().toString());
        rows.unpause(projectId, row.id());

        StructuredLog.info(log, Markers.OPS, "classifier.reset")
                .message("classifier reset; its next sweep starts from the first kept trace")
                .field("project", projectId)
                .field("classifier", row.classifierKey())
                .field("actor", actor)
                .field("job", restart.name())
                .field("detections_deleted", detectionsDeleted)
                .field("assessments_deleted", assessmentsDeleted)
                .field("learned_state_deleted", learnedStateDeleted)
                .field("findings_closed", findingsClosed)
                .field("enabled", row.enabled())
                .log();
        return classifiers.get(projectId, id);
    }
}
