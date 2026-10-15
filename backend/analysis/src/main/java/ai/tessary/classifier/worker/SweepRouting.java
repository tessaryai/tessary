// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A detector kind's sweep grain: the one place combining {@link BuiltInClassifierCatalog} and
 * {@link ClassifierSweepRegistry}, so {@link ClassifierWorker} and {@code ClassifierService} never disagree. A
 * catalog-declared kind keeps its grain even when a sweep claims it, so a jar never changes a shipped classifier. An
 * undeclared kind runs at window grain when a sweep claims it.
 */
@Component
public class SweepRouting {

    public enum Route {
        CATALOG,
        REGISTRY;

        /** The {@code route} log field. */
        public String field() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Routing(Grain grain, Route route) {}

    private final BuiltInClassifierCatalog catalog;
    private final ClassifierSweepRegistry sweeps;

    public SweepRouting(BuiltInClassifierCatalog catalog, ClassifierSweepRegistry sweeps) {
        this.catalog = catalog;
        this.sweeps = sweeps;
    }

    public Routing routeFor(String detectorKind) {
        if (catalog.declares(detectorKind)) {
            return new Routing(catalog.grainFor(detectorKind), Route.CATALOG);
        }
        if (sweeps.forKind(detectorKind).isPresent()) {
            return new Routing(Grain.WINDOW, Route.REGISTRY);
        }
        return new Routing(catalog.grainFor(detectorKind), Route.CATALOG);
    }

    public Grain grainFor(String detectorKind) {
        return routeFor(detectorKind).grain();
    }

    /** Empty unless the kind sweeps at window grain, the only grain {@link ClassifierWorker} hands to a sweep. */
    public Optional<ClassifierSweep> sweepFor(String detectorKind) {
        return grainFor(detectorKind) == Grain.WINDOW ? sweeps.forKind(detectorKind) : Optional.empty();
    }
}
