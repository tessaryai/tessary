// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;
import ai.tessary.classifier.catalog.DetectorSupplier;
import org.springframework.stereotype.Component;

/**
 * Hands the catalog the Frustration detector. The detector is a Spring bean because it needs the
 * decision client, the provider resolver and its own repositories, which the catalog's shared
 * {@link Deps} do not carry, so the frustration module declares no factory and this supplies it.
 */
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
