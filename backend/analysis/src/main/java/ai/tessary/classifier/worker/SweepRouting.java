// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The grain a detector kind sweeps at, and where that answer came from: the one place that combines
 * {@link BuiltInClassifierCatalog} and {@link ClassifierSweepRegistry}. {@link ClassifierWorker}
 * dispatches on it, and {@code ClassifierService} reads it to decide whether widening a classifier's
 * call sites rewinds its cursor, so the two never disagree about a kind's grain.
 *
 * <p>The catalog answers for a kind it declares, even when a sweep also claims it, so a jar on the
 * classpath never changes a shipped classifier's grain. A kind no module declares runs at window
 * grain when a sweep claims it; with no sweep it keeps the catalog's default.
 */
@Component
public class SweepRouting {

    /** Where a kind's grain came from: the catalog's manifest or default, or a sweep in the registry. */
    public enum Route {
        CATALOG,
        REGISTRY;

        /** The value of the {@code route} log field. */
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
}
