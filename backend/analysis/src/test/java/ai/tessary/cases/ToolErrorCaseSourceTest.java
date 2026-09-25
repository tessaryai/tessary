// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingRowBuilder;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A tool-error finding shaped into its case. One case per tool, whichever direction moved, so the bugs are a
 * case keyed off anything but the tool's bucket (a second case for the same tool), a basis that miscounts the
 * error patterns, and an unreadable blob that drops the case or keys it off the whole cause key.
 */
class ToolErrorCaseSourceTest {

    private final ToolErrorCaseSource source = new ToolErrorCaseSource();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "[]|",
                "[{}]|' One error pattern accounts for it.'",
                "[{},{},{}]|' Across 3 error patterns.'",
            })
    void theBasisQuotesTheRateMoveAndHowManyPatternsCarryIt(String patterns, String patternSentence) {
        FindingRow finding = FindingRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .payload("{\"cause_kind\":\"rate_shift\",\"native_cause_key\":\"tool_error_rate:tool:search_docs:up\","
                        + "\"bucket\":{\"key\":\"tool:search_docs\"},\"rate\":{\"ref\":0.05,\"cur\":0.4},"
                        + "\"delta_pp\":35.0,\"criticality\":60,\"patterns\":" + patterns + "}")
                .build();

        assertEquals(
                new CaseDetection(
                        new CaseKey(
                                CaseRow.Detector.TOOL_ERROR,
                                CaseRow.SubjectKind.TOOL,
                                "tool:search_docs",
                                "tool_error_rate"),
                        "search_docs",
                        null,
                        "fnd-1",
                        "search_docs showing elevated error rates",
                        "Sustained change against this tool's own past failure rate, 5.0% to 40.0% (+35.00pp)."
                                + (patternSentence == null ? "" : patternSentence),
                        0.5,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        0.4,
                        0.05,
                        35.0),
                source.shape(finding));
    }

    /**
     * No readable bucket in the blob: the case still opens, keyed on the tool named inside the cause key
     * ({@code tool_error_rate:tool:search_docs:up} is the tool {@code tool:search_docs}), with no numbers
     * and the lowest rank. A key with no measure or direction segments to strip is taken whole. An onset the
     * parser cannot read leaves the case unbracketed rather than failing the open.
     */
    @ParameterizedTest
    @CsvSource({
        "tool_error_rate:tool:search_docs:up, tool:search_docs, search_docs",
        "search_docs, search_docs, search_docs"
    })
    void anUnreadableBlobKeysTheCaseOnTheToolInsideTheCauseKey(String nativeKey, String bucket, String label) {
        FindingRow finding = FindingRowBuilder.of(BuiltInDetector.Kind.TOOL_ERROR)
                .onsetAt("not a timestamp")
                .payload("{\"cause_kind\":\"rate_shift\",\"native_cause_key\":\"" + nativeKey + "\"}")
                .build();

        assertEquals(
                new CaseDetection(
                        new CaseKey(CaseRow.Detector.TOOL_ERROR, CaseRow.SubjectKind.TOOL, bucket, "tool_error_rate"),
                        label,
                        null,
                        "fnd-1",
                        nativeKey + " has shifted",
                        "A sustained change in this tool's failure rate, with unreadable evidence.",
                        0.0,
                        null,
                        null,
                        null,
                        null),
                source.shape(finding));
    }
}
