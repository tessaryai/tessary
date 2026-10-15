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
 * Whether the groundedness model is scoring, and if not, whether it ever has. "Ever" is a moved sweep cursor, since
 * only a sweep whose model answered moves it. A project with no traffic since setup, or a cursor rewound while the
 * model was down, misreads as never set up; harmless, because setup detects an existing install.
 */
@Service
public class GroundednessStatus {

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

    /** 422s for any classifier other than groundedness. */
    public GroundednessStatusView view(String projectId, ClassifierRow row) {
        if (!BuiltInDetector.Kind.GROUNDEDNESS.equals(row.detector())) {
            throw new TessaryException(ClassifierError.NOT_GROUNDEDNESS, row.classifierKey());
        }
        EncoderAvailability.Snapshot health = encoder.snapshot();
        Optional<ClassifierJobRow> job = jobs.findByClassifier(projectId, row.id());
        boolean everSwept = job.map(j -> j.cursorAt() != null).orElse(false);
        @Nullable Instant caughtUp = jobs.caughtUpAt(projectId, row.id()).orElse(null);
        @Nullable
        Instant lastScored = assessments.lastScoredAt(projectId, row.id()).orElse(null);
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
     * In order: disabled is {@code off}. In dev mode an answering model is {@code on}. In production the model sleeps
     * between runs, so {@code on} is a sweep caught up within {@code missedRun}. Otherwise swept-once is
     * {@code not_scoring} and never-swept is {@code not_set_up}.
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
