// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRowBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A finding from a classifier with no dedicated case source, shaped into its case. The bugs are a case with
 * no subject when the detector wrote no label, a basis that names the wrong authority or drops the
 * detector's own sentence, a severity outside the ranked list's 0..1, and an onset text the parser cannot
 * read failing the whole open.
 */
class GenericFindingCaseSourceTest {

    private final GenericFindingCaseSource source = new GenericFindingCaseSource();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // a labelled triage-confirmed finding, severity past the top of the scale
                "Refund bot|p95 rose.||1.7|2026-09-01T00:00:00Z|Refund bot|A triage run audited this claim and found"
                        + " it sound. p95 rose.|1.0|2026-09-01T00:00:00Z",
                // unlabelled, blank basis, human-ruled, no asserted severity, onset in Postgres's rendering
                "|' '|2026-09-03T00:00:00Z||2026-09-01 00:00:00+00|refusal-spike|A human ruled this a real"
                        + " deviation.|0.5|",
                // no basis at all, severity below the bottom, blank onset
                "Refund bot|||-0.2||Refund bot|A triage run audited this claim and found it sound.|0.0|",
            })
    void theCaseQuotesTheDetectorsOwnTermsUnderWhoeverRuledIt(
            @Nullable String label,
            @Nullable String basis,
            @Nullable String humanAt,
            @Nullable Double severity,
            @Nullable String onsetAt,
            String expectedLabel,
            String expectedBasis,
            double expectedSeverity,
            @Nullable String expectedOnset) {
        var finding = FindingRowBuilder.of(BuiltInDetector.Kind.REGEX)
                .causeKey("sig-1:refusal-spike")
                .subjectLabel(label)
                .basis(basis)
                .humanVerdictAt(humanAt)
                .severity(severity)
                .onsetAt(onsetAt == null ? "" : onsetAt)
                .payload("{\"native_cause_key\":\"refusal-spike\"}")
                .build();

        assertEquals(
                new CaseDetection(
                        new CaseKey(
                                CaseRow.Detector.CLASSIFIER,
                                CaseRow.SubjectKind.CLASSIFIER,
                                "sig-1:refusal-spike",
                                BuiltInDetector.Kind.REGEX),
                        expectedLabel,
                        null,
                        "fnd-1",
                        "refusal-spike",
                        expectedBasis,
                        expectedSeverity,
                        expectedOnset == null ? null : java.time.Instant.parse(expectedOnset),
                        null,
                        null,
                        null),
                source.shape(finding));
    }
}
