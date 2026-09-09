// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTable.Grain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers this build's classifiers' {@link DetectionTable}s with {@code DetectionTableRegistry}.
 *
 * <p>{@code REGEX} claims {@code user_classifier_detection}: "this text matched a user-authored
 * rule," parameterized by a classifier row. {@code DetectionTableRegistry} keys on
 * {@code detectorKind}, not {@code table}, so more than one kind can share a table.
 */
@Configuration
public class OpenDetectionTables {

    @Bean
    public DetectionTable secretLeakDetectionTable() {
        return new DetectionTable(BuiltInDetector.Kind.SECRET_LEAK, "secret_leak_detection", Grain.SPAN);
    }

    @Bean
    public DetectionTable malformedOutputDetectionTable() {
        return new DetectionTable(BuiltInDetector.Kind.MALFORMED_OUTPUT, "malformed_output_detection", Grain.SPAN);
    }

    @Bean
    public DetectionTable regexDetectionTable() {
        return new DetectionTable(BuiltInDetector.Kind.REGEX, "user_classifier_detection", Grain.SPAN);
    }
}
