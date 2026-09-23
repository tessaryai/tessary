// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Answers one operational question, on every boot and once a day: <b>can the encoder be turned off?</b>
 *
 * <p>The groundedness model must stay running while any org still uses an encoder classifier, and the
 * hazard is specific rather than hypothetical. {@code groundedness} seeds off, so the model can serve
 * nothing at all: a GPU instance with no visible traffic, on a bill somebody will eventually read. The
 * encoder classifier does not fail loudly when the model goes either: its sweeps pause until it answers
 * again, which reads like a quiet week.
 *
 * <p>So this counts what actually depends on the model — a project with an <em>enabled</em> encoder-backed
 * classifier row <em>and</em> the org capability to run it, since either one being off is enough to make the
 * row inert — and prints the number where an operator will find it. Whether the model answers right now
 * is deliberately not part of the count: a model that is down is still depended on. Zero is the only
 * value at which the model may be turned off, and that answer is a log query rather than a guess about
 * who uses what.
 *
 * <p>It reports; it never enforces. The decision to run or not run a deployment is not one a request handler
 * should be taking, and the same posture is why {@code PlatformSpendReporter} prints rather than throttles.
 */
@Component
public class EncoderDependencyReporter {

    private static final Logger log = LoggerFactory.getLogger(EncoderDependencyReporter.class);

    private final ProjectRepository projects;
    private final ClassifierRepository classifiers;
    private final CapabilityService capabilities;

    public EncoderDependencyReporter(
            ProjectRepository projects, ClassifierRepository classifiers, CapabilityService capabilities) {
        this.projects = projects;
        this.classifiers = classifiers;
        this.capabilities = capabilities;
    }

    /**
     * On boot, and never fatally. A listener on {@code ApplicationReadyEvent} that throws fails the whole
     * context, and a diagnostic that can stop the platform starting is a worse bug than the one it
     * reports.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reportOnBoot() {
        try {
            report();
        } catch (RuntimeException e) {
            log.warn(
                    "encoder dependency report failed on boot error={}",
                    e.getClass().getSimpleName());
        }
    }

    @Scheduled(
            fixedDelayString = "${tessary.classifier.encoder-dependency-report-ms:86400000}",
            initialDelay = 86_400_000)
    public void reportDaily() {
        report();
    }

    /** What the report counted: the projects and orgs that depend on the encoder, and through which classifiers. */
    record Dependency(int projects, int orgs, Set<String> classifiers) {
        boolean decommissionable() {
            return projects == 0;
        }
    }

    Dependency report() {
        Set<String> orgs = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        int dependentProjects = 0;
        for (Project project : projects.findActive()) {
            Set<String> live = liveEncoderKeys(project);
            if (live.isEmpty()) continue;
            dependentProjects++;
            orgs.add(project.orgId());
            keys.addAll(live);
        }
        Dependency d = new Dependency(dependentProjects, orgs.size(), keys);
        StructuredLog.info(log, Markers.OPS, "encoder.dependency")
                .field("projects", d.projects())
                .field("orgs", d.orgs())
                .field("classifiers", d.classifiers())
                .field("decommissionable", d.decommissionable())
                .log();
        return d;
    }

    /** The encoder-backed classifiers this project would actually run: row enabled AND capability held. */
    private Set<String> liveEncoderKeys(Project project) {
        Set<String> live = new LinkedHashSet<>();
        for (ClassifierRow row : classifiers.listEnabled(project.id())) {
            if (!Kind.ENCODER_BACKED.contains(row.detector())) continue;
            Optional<Capability> capability = capabilityFor(row.detector());
            if (capability.isPresent() && !capabilities.isEnabled(project.orgId(), capability.get())) continue;
            live.add(row.classifierKey());
        }
        return live;
    }

    private static Optional<Capability> capabilityFor(String detector) {
        return switch (detector) {
            case Kind.GROUNDEDNESS -> Optional.of(Capability.GROUNDEDNESS);
            default -> Optional.empty();
        };
    }
}
