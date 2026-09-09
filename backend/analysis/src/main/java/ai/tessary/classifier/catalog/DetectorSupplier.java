// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;

/**
 * A {@link BuiltInDetector} contributed from outside this catalog's in-tree {@link
 * BuiltInClassifierCatalog#MODULES} manifest: a Spring-discovered bean, collected at construction
 * time, giving observation/turn-grain detector dispatch the same external injection point {@code
 * ClassifierSweep} already has for trace/window grain.
 *
 * <p>There is no membership check against {@code MODULES}: a supplier can claim a kind the
 * catalog never declares. It builds and registers, but dispatch never fires, since seeding and
 * grain routing read {@code MODULES} independently of this seam. Two sources claiming the same
 * kind fails loud instead: {@link BuiltInClassifierCatalog}'s map build throws {@link
 * IllegalStateException} rather than letting classpath order pick a winner.
 */
@FunctionalInterface
public interface DetectorSupplier {

    /** Builds the detector this supplier contributes, from the shared, catalog-owned dependencies. */
    BuiltInDetector build(Deps deps);
}
