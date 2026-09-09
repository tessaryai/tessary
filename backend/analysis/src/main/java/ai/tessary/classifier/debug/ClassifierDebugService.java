// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.debug;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.classifier.debug.ClassifierDebugDtos.BehaviorProfileDebugView;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Assembles the debug bundle for one classifier, read-only against tables the production sweep
 * already writes: {@code job} (via {@link ClassifierJobRepository}) and {@code metric_baseline}
 * directly, and each classifier family's own fitted state through {@link ClassifierDebugContributor}.
 * Nothing here is computed fresh.
 */
@Service
public class ClassifierDebugService {

    private static final int RULE_SAMPLE_PER_EPOCH = 60;
    private static final int FINDING_SAMPLE = 50;

    private final ClassifierService classifiers;
    private final ClassifierJobRepository jobs;
    private final MetricBaselineRepository metricBaselines;
    /**
     * The per-family fitted-state readers, discovered rather than referenced, so a family's own
     * fitted state (behavior drift's epoch store, for example) never needs to be a field here. See
     * {@link ClassifierDebugContributor}.
     */
    private final List<ClassifierDebugContributor> contributors;

    private final ObjectMapper mapper;

    public ClassifierDebugService(
            ClassifierService classifiers,
            ClassifierJobRepository jobs,
            MetricBaselineRepository metricBaselines,
            ObjectProvider<ClassifierDebugContributor> contributors,
            ObjectMapper mapper) {
        this.classifiers = classifiers;
        this.jobs = jobs;
        this.metricBaselines = metricBaselines;
        // ObjectProvider, not List<T>: that is the difference between degrading and not booting. A
        // required constructor `List<T>` parameter with no candidate bean is an unsatisfied dependency
        // in Spring, not an empty list; resolveMultipleBeans returns null and doResolveDependency
        // raises NoSuchBeanDefinitionException. So a deployment that registers no contributor for
        // this port would fail to start, with no compile error and nothing to grep for, while the
        // field's own javadoc promises it degrades to what a classifier with no data shows. Held by
        // AbsentAdapterContextTest.
        this.contributors = contributors.orderedStream().toList();
        this.mapper = mapper;
    }

    /**
     * The debug family a detector belongs to: {@code BuiltInClassifierCatalog}'s three execution tiers,
     * plus behaviour drift split out of "fitting" since its debug content (an n-gram profile) has nothing
     * in common with a metric baseline's numeric sketch.
     */
    private static String familyOf(String detector) {
        if (Kind.ENCODER_BACKED.contains(detector)) return Family.ENCODER;
        return switch (detector) {
            case Kind.COST_DRIFT, Kind.DURATION_DRIFT -> Family.METRIC_DRIFT;
            case Kind.BEHAVIOR_DRIFT -> Family.BEHAVIOR_DRIFT;
            default -> Family.DETERMINISTIC; // secret_leak, malformed_output, regex, classifier, inert
        };
    }

    public ClassifierDebugView debug(String projectId, String id) {
        ClassifierRow row = classifiers.get(projectId, id); // tenant + existence guard
        String family = familyOf(row.detector());
        SweepView sweep = sweepFor(projectId, row.id());

        List<MetricBaselineView> metric = null;
        List<BehaviorProfileDebugView> behavior = null;
        if (Family.METRIC_DRIFT.equals(family)) {
            double w1Floor = MetricDriftConfig.of(mapper, row.configJson()).w1Floor();
            metric = metricBaselines.listByClassifier(projectId, row.id()).stream()
                    .map(b -> toMetricView(b, w1Floor))
                    .toList();
        } else if (Family.BEHAVIOR_DRIFT.equals(family)) {
            // Null when no contributor is registered for the family, which is what every other family
            // already renders here, not an error and not an empty list, which would claim the
            // classifier has been swept and holds no epochs.
            behavior = contributors.stream()
                    .filter(c -> family.equals(c.family()))
                    .findFirst()
                    .map(c -> c.profiles(projectId, row.id()))
                    .orElse(null);
        }
        return new ClassifierDebugView(row.id(), row.detector(), family, sweep, metric, behavior);
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
