// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTable.Grain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the three open classifiers' {@link DetectionTable}s with {@code DetectionTableRegistry}
 * — the open half of the registration this issue makes universal; groundedness, behaviour-drift and
 * frustration register their own {@link DetectionTable} beans from their own paid modules.
 *
 * <p>{@code REGEX} claims {@code user_classifier_detection} — "this text matched a user-authored
 * rule" parameterised by a classifier row, exactly as {@code ClassifierDetectionWriteRepository}'s
 * old {@code TABLES} map already had it. The table used to be shared with the now-removed
 * {@code CLASSIFIER} (centroid) kind; {@code DetectionTableRegistry} keys on {@code detectorKind},
 * not on {@code table}, so a single-owner table is no different from a shared one here.
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
