// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTable.Grain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers this build's {@link DetectionTable}s. The registry keys on {@code detectorKind}, so kinds can share a
 * table. {@code FRUSTRATION} is trace grain (a turn is a trace) and the one table the trace and session views show. It
 * and {@code GROUNDEDNESS} are clearable by a false-alarm close.
 */
@Configuration
public class OpenDetectionTables {

    @Bean
    public DetectionTable secretLeakDetectionTable() {
        return new DetectionTable(BuiltInDetector.Kind.SECRET_LEAK, "secret_leak_detection", Grain.SPAN, false, false);
    }

    @Bean
    public DetectionTable malformedOutputDetectionTable() {
        return new DetectionTable(
                BuiltInDetector.Kind.MALFORMED_OUTPUT, "malformed_output_detection", Grain.SPAN, false, false);
    }

    @Bean
    public DetectionTable frustrationDetectionTable() {
        return new DetectionTable(BuiltInDetector.Kind.FRUSTRATION, "frustration_detection", Grain.TRACE, true, true);
    }

    @Bean
    public DetectionTable groundednessDetectionTable() {
        return new DetectionTable(BuiltInDetector.Kind.GROUNDEDNESS, "groundedness_detection", Grain.SPAN, false, true);
    }

    @Bean
    public DetectionTable regexDetectionTable() {
        return new DetectionTable(BuiltInDetector.Kind.REGEX, "user_classifier_detection", Grain.SPAN, false, false);
    }
}
