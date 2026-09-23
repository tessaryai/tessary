// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;
import ai.tessary.classifier.catalog.DetectorSupplier;
import org.springframework.stereotype.Component;

/**
 * Hands the catalog the Groundedness detector. It is built here rather than in the catalog's module
 * list because it writes its own {@code groundedness_assessment} rows, a repository the catalog's
 * shared {@link Deps} do not carry, the way Frustration's detector arrives with its own.
 */
@Component
public class GroundednessDetectorSupplier implements DetectorSupplier {

    private final GroundednessAssessmentRepository assessments;

    public GroundednessDetectorSupplier(GroundednessAssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @Override
    public BuiltInDetector build(Deps deps) {
        return new GroundednessDetector(
                deps.encoderScorer(), deps.substrate(), deps.substrate(), assessments, deps.mapper());
    }
}
