// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import ai.tessary.cases.CaseOpener;
import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Reading a classifier's {@code arming} block out of its {@code config_json}, a text column a person edits.
 * The bugs are a malformed config throwing (and stopping the classifier from detecting, which cannot be
 * recomputed later), and a zero or negative threshold or window arming a gate that fires on nothing or
 * never closes.
 */
@ExtendWith(MockitoExtension.class)
class ClassifierArmingTest {

    @Mock
    ClassifierDetectionWriteRepository detections;

    @Mock
    FindingRepository findings;

    @Mock
    FindingEvidenceRepository evidence;

    @Mock
    CaseOpener caseOpener;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{not json|||",
                "{\"arming\": 3}|||",
                "{\"arming\": {\"basis\": \"distinct_users\", \"threshold\": 0, \"window_seconds\": -5,"
                        + " \"confidence\": \"high\"}}|distinct_users|1|1",
                "{\"arming\": {}}|event_count|1|86400",
            })
    void aConfigReadsAsArmedOnlyWithASaneBlock(
            String configJson, @Nullable String basis, @Nullable Long threshold, @Nullable Long window) {
        when(detections.writesDetections(BuiltInDetector.Kind.SECRET_LEAK)).thenReturn(true);
        ClassifierArming arming = new ClassifierArming(detections, findings, evidence, new ObjectMapper(), caseOpener);

        ClassifierArming.@Nullable Config expected = basis == null
                ? null
                : new ClassifierArming.Config(
                        basis,
                        Objects.requireNonNull(threshold),
                        Objects.requireNonNull(window),
                        configJson.contains("confidence") ? "high" : null);
        assertEquals(expected, arming.configOf(signal(configJson)));
    }

    private static ClassifierRow signal(String configJson) {
        String kind = BuiltInDetector.Kind.SECRET_LEAK;
        return new ClassifierRow(
                "sig-1",
                "proj-1",
                kind,
                kind,
                null,
                kind,
                configJson,
                true,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                "now",
                "now");
    }
}
