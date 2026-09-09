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
 * Answers one operational question, on every boot and once a day: <b>can classify-service be turned off?</b>
 *
 * <p>Launch requirement J1 is "the encoder service stays running while any org still uses an encoder
 * classifier", and the hazard it names is specific rather than hypothetical. Decision D3 flags
 * {@code frustration} and {@code groundedness} off for every partner, so the classify-service task serves
 * nothing but our own orgs — an ECS task with no visible traffic, on a bill somebody will eventually read.
 * The two encoder classifiers do not fail loudly when it goes: the sweep throws, retries, and the signal
 * simply stops producing, which reads like a quiet week.
 *
 * <p>So this counts what actually depends on the service — a project with an <em>enabled</em> encoder-backed
 * classifier row <em>and</em> the org capability to run it, since either one being off is enough to make the
 * row inert — and prints the number where an operator will find it. Zero is the only value at which the
 * service may be scaled down, and that answer is a log query rather than a guess about who uses what.
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

    private void report() {
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
        StructuredLog.info(log, Markers.OPS, "encoder.dependency")
                .field("projects", dependentProjects)
                .field("orgs", orgs.size())
                .field("classifiers", keys)
                .field("decommissionable", dependentProjects == 0)
                .log();
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
            case Kind.FRUSTRATION -> Optional.of(Capability.FRUSTRATION);
            case Kind.GROUNDEDNESS -> Optional.of(Capability.GROUNDEDNESS);
            default -> Optional.empty();
        };
    }
}
