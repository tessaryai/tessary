// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;
import ai.tessary.classifier.catalog.DetectorSupplier;
import org.springframework.stereotype.Component;

/** The detector is a Spring bean because it needs collaborators the catalog's {@link Deps} do not carry. */
@Component
public class FrustrationDetectorSupplier implements DetectorSupplier {

    private final JevFrustrationDetector detector;

    public FrustrationDetectorSupplier(JevFrustrationDetector detector) {
        this.detector = detector;
    }

    @Override
    public BuiltInDetector build(Deps deps) {
        return detector;
    }
}
