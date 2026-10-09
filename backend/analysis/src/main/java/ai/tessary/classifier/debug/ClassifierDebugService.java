// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.debug;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.classifier.debug.ClassifierDebugDtos.ClassifierDebugView;
import ai.tessary.classifier.debug.ClassifierDebugDtos.ClassifierDebugView.Family;
import ai.tessary.classifier.debug.ClassifierDebugDtos.MetricBaselineView;
import ai.tessary.classifier.debug.ClassifierDebugDtos.SketchSummary;
import ai.tessary.classifier.debug.ClassifierDebugDtos.SweepView;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricControl;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricSketch;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Assembles the debug bundle for one classifier, read-only against tables the production sweep
 * already writes: {@code job} (via {@link ClassifierJobRepository}) and {@code metric_baseline}.
 * Nothing here is computed fresh.
 */
@Service
public class ClassifierDebugService {

    private final ClassifierService classifiers;
    private final ClassifierJobRepository jobs;
    private final MetricBaselineRepository metricBaselines;
    private final ObjectMapper mapper;

    public ClassifierDebugService(
            ClassifierService classifiers,
            ClassifierJobRepository jobs,
            MetricBaselineRepository metricBaselines,
            ObjectMapper mapper) {
        this.classifiers = classifiers;
        this.jobs = jobs;
        this.metricBaselines = metricBaselines;
        this.mapper = mapper;
    }

    /**
     * The debug family a detector belongs to: {@code BuiltInClassifierCatalog}'s execution tiers.
     */
    private static String familyOf(String detector) {
        if (Kind.ENCODER_BACKED.contains(detector)) return Family.ENCODER;
        return switch (detector) {
            case Kind.FRUSTRATION -> Family.DECISION;
            case Kind.COST_DRIFT, Kind.DURATION_DRIFT -> Family.METRIC_DRIFT;
            default -> Family.DETERMINISTIC; // secret_leak, malformed_output, regex, classifier
        };
    }

    public ClassifierDebugView debug(String projectId, String id) {
        ClassifierRow row = classifiers.get(projectId, id); // tenant + existence guard
        String family = familyOf(row.detector());
        SweepView sweep = sweepFor(projectId, row.id());

        List<MetricBaselineView> metric = null;
        if (Family.METRIC_DRIFT.equals(family)) {
            double w1Floor = MetricDriftConfig.of(mapper, row.configJson()).w1Floor();
            metric = metricBaselines.listByClassifier(projectId, row.id()).stream()
                    .map(b -> toMetricView(b, w1Floor))
                    .toList();
        }
        return new ClassifierDebugView(row.id(), row.detector(), family, sweep, metric);
    }

    /** The one job row for this signal, or the "never enqueued" empty state {@code health()} also uses. */
    private SweepView sweepFor(String projectId, String classifierId) {
        return jobs.listByProject(projectId).stream()
                .filter(j -> j.classifierId().equals(classifierId))
                .findFirst()
                .map(ClassifierDebugService::toSweepView)
                .orElseGet(SweepView::neverSwept);
    }

    private static SweepView toSweepView(ClassifierJobRow job) {
        return new SweepView(
                job.status(),
                job.attempts(),
                job.lastError(),
                job.cursorAt(),
                job.cursorId(),
                job.leaseOwner(),
                job.leaseExpiresAt(),
                job.updatedAt());
    }

    private MetricBaselineView toMetricView(MetricBaselineRow b, double w1Floor) {
        return new MetricBaselineView(
                b.measure(),
                b.bucketKind(),
                b.bucketKey(),
                b.state(),
                w1Floor,
                b.currentCount(),
                b.currentOpenedAt(),
                b.lastEventAt(),
                b.pinnedAt(),
                sketchSummary(b.pinnedSketchJson()),
                // The control's newest day rather than the retired prev slot. A debug view exists to
                // answer "what is this bucket being judged against", and after the rolling control
                // landed the honest answer to that is the ring, of which this is the freshest slice.
                sketchSummary(newestControlDay(b)),
                sketchSummary(b.currentSketchJson()));
    }

    private static @Nullable String newestControlDay(MetricBaselineRow b) {
        MetricControl.Day day = MetricControl.fromJson(b.controlJson()).newest();
        return day == null ? null : day.sketchJson();
    }

    /** Parses just enough of a sketch to be useful in a debug view: how full it is, and its grid identity. */
    private static @Nullable SketchSummary sketchSummary(@Nullable String sketchJson) {
        if (sketchJson == null || sketchJson.isBlank()) return null;
        try {
            MetricSketch sketch = MetricSketch.fromJson(sketchJson);
            return new SketchSummary(sketch.count(), sketch.gridId());
        } catch (RuntimeException e) {
            // A sketch predating a grid/format change should not 500 the debug view; it should say so.
            return null;
        }
    }
}
