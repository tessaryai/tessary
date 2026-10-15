// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;

/**
 * A {@link BuiltInDetector} contributed as a Spring bean, outside {@link BuiltInClassifierCatalog#MODULES}. A kind the
 * catalog never declares builds but never fires, since seeding and routing read {@code MODULES}. Two sources claiming
 * one kind throw {@link IllegalStateException} rather than letting classpath order pick.
 */
@FunctionalInterface
public interface DetectorSupplier {

    BuiltInDetector build(Deps deps);
}
