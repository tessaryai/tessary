// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.ClassifierDtos.GroundednessStatusView;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.config.AppVersion;
import ai.tessary.config.GroundednessProperties;
import ai.tessary.config.GroundednessProperties.Mode;
import ai.tessary.config.ObserverProperties;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.EncoderAvailability;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The groundedness row's status: is the model scoring, and if not, has it ever. Read by the classifier
 * row, the setup modal while it waits for the model, and the restart notice.
 *
 * <p>"Has it ever" is the sweep cursor: only a sweep whose model answered moves it, so a moved cursor
 * means the model was set up once. It misreads a project with no traffic since setup, and a cursor
 * rewound while the model was down, as never set up; the setup detects an existing install and goes
 * straight to the restart, so both are harmless.
 */
@Service
public class GroundednessStatus {

    /** The row's four states. */
    public enum State {
        OFF,
        ON,
        NOT_SCORING,
        NOT_SET_UP;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ClassifierJobRepository jobs;
    private final GroundednessAssessmentRepository assessments;
    private final EncoderAvailability encoder;
    private final GroundednessProperties groundedness;
    private final ObserverProperties observer;

    public GroundednessStatus(
            ClassifierJobRepository jobs,
            GroundednessAssessmentRepository assessments,
            EncoderAvailability encoder,
            GroundednessProperties groundedness,
            ObserverProperties observer) {
        this.jobs = jobs;
        this.assessments = assessments;
        this.encoder = encoder;
        this.groundedness = groundedness;
        this.observer = observer;
    }

    /** The status of {@code row}, which must be the groundedness classifier; 422s for any other. */
    public GroundednessStatusView view(String projectId, ClassifierRow row) {
        if (!BuiltInDetector.Kind.GROUNDEDNESS.equals(row.detector())) {
            throw new TessaryException(ClassifierError.NOT_GROUNDEDNESS, row.classifierKey());
        }
        EncoderAvailability.Snapshot health = encoder.snapshot();
        Optional<ClassifierJobRow> job = jobs.findByClassifier(projectId, row.id());
        boolean everSwept = job.map(j -> j.cursorAt() != null).orElse(false);
        @Nullable Instant caughtUp = jobs.caughtUpAt(projectId, row.id()).orElse(null);
        @Nullable Instant lastScored = assessments.lastScoredAt(projectId, row.id()).orElse(null);
        Mode mode = groundedness.mode();
        State state = state(
                row.enabled(),
                mode,
                health.available(),
                everSwept,
                caughtUp,
                Duration.ofMinutes(groundedness.getProductionMissedRunMinutes()),
                Instant.now());
        String url = observer.getEncoder().getUrl();
        return new GroundednessStatusView(
                state.wire(),
                mode.wire(),
                url != null && !url.isBlank(),
                health.available(),
                health.reason(),
                health.checkedAt() == null ? null : health.checkedAt().toString(),
                everSwept,
                lastScored == null ? null : lastScored.toString(),
                caughtUp == null ? null : caughtUp.toString(),
                AppVersion.gitRef(AppVersion.current()));
    }

    /**
     * The rules, in the order they apply. A disabled row is {@code off} whatever the model does. In dev
     * mode the model answering is {@code on}. In production mode the model sleeps between scheduled
     * runs, so {@code on} is a sweep that caught up within {@code missedRun}, and a model that answers
     * before its first sweep has caught up is on its way there. Otherwise a classifier that was swept
     * once is {@code not_scoring}, and one that never was is {@code not_set_up}.
     */
    static State state(
            boolean enabled,
            Mode mode,
            boolean available,
            boolean everSwept,
            @Nullable Instant caughtUpAt,
            Duration missedRun,
            Instant now) {
        if (!enabled) return State.OFF;
        if (mode == Mode.PRODUCTION) {
            boolean recent = caughtUpAt != null && !now.isAfter(caughtUpAt.plus(missedRun));
            if (recent) return State.ON;
            if (everSwept) return State.NOT_SCORING;
            return available ? State.ON : State.NOT_SET_UP;
        }
        if (available) return State.ON;
        return everSwept ? State.NOT_SCORING : State.NOT_SET_UP;
    }
}
