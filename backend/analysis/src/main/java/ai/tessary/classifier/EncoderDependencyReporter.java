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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Logs, on boot and daily, how many projects depend on the groundedness model (an enabled encoder row AND the
 * capability), so an operator knows whether it can be turned off. Availability is excluded: a down model is still
 * depended on. Reports, never enforces.
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

    /** Never fatal: a throwing {@code ApplicationReadyEvent} listener fails the context. */
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

    /** Row enabled AND capability held. */
    private Set<String> liveEncoderKeys(Project project) {
        Set<String> live = new LinkedHashSet<>();
        for (ClassifierRow row : classifiers.listEnabled(project.id())) {
            if (!Kind.ENCODER_BACKED.contains(row.detector())) continue;
            // GROUNDEDNESS is the only encoder-backed kind.
            if (!capabilities.isEnabled(project.orgId(), Capability.GROUNDEDNESS)) continue;
            live.add(row.classifierKey());
        }
        return live;
    }
}
