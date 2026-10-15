// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;
import ai.tessary.classifier.catalog.DetectorSupplier;
import org.springframework.stereotype.Component;

/** Built here because it writes {@code groundedness_assessment} rows, which the catalog's {@link Deps} do not carry. */
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
