// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingRowBuilder;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * A malformed-output finding shaped into the case it opens. The case keys on the call site and quotes the
 * finding's flat rate payload, so the bugs are a case keyed off the wrong field, a basis quoting the wrong
 * counts, and a severity that disagrees with tool_error's squash for the same criticality.
 */
class MalformedOutputCaseSourceTest {

    private final MalformedOutputCaseSource source = new MalformedOutputCaseSource();

    @Test
    void theCaseKeysOnTheCallSiteAndQuotesTheFindingsOwnRate() {
        FindingRow finding = FindingRowBuilder.of(BuiltInDetector.Kind.MALFORMED_OUTPUT)
                .callSiteId("checkout-agent")
                .payload("{\"cause_kind\":\"malformed_rate\",\"native_cause_key\":\"checkout-agent\","
                        + "\"baseline_rate\":0.02,\"current_rate\":0.25,\"delta_pp\":23.0,\"baseline_calls\":400,"
                        + "\"calls_since_onset\":40,\"failures_since_onset\":10,\"criticality\":60}")
                .build();

        assertEquals(
                new CaseDetection(
                        new CaseKey(
                                CaseRow.Detector.MALFORMED_OUTPUT,
                                CaseRow.SubjectKind.CALL_SITE,
                                "checkout-agent",
                                "malformed_output_rate"),
                        "checkout-agent",
                        "checkout-agent",
                        "fnd-1",
                        "checkout-agent outputs failing their schema",
                        "10 of the 40 outputs since 2026-09-01T00:00:00Z failed their schema. Fitted rate 2.0% over"
                                + " 400 calls.",
                        // criticality 60 squashes to 60 / (60 + 60), tool_error's own curve
                        0.5,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        0.25,
                        0.02,
                        23.0),
                source.shape(finding));
    }

    /**
     * A payload from before criticality was written sorts last instead of borrowing a rank, and an onset
     * stored in Postgres's own timestamp rendering leaves the case unbracketed instead of failing the open.
     */
    @Test
    void aFindingWithNoCriticalityAndAnUnparseableOnsetStillOpensItsCase() {
        FindingRow finding = FindingRowBuilder.of(BuiltInDetector.Kind.MALFORMED_OUTPUT)
                .callSiteId("checkout-agent")
                .onsetAt("2026-09-01 00:00:00+00")
                .payload("{\"cause_kind\":\"malformed_rate\",\"native_cause_key\":\"checkout-agent\"}")
                .build();

        CaseDetection detection = source.shape(finding);

        assertEquals(0.0, detection.severity());
        assertEquals(null, detection.onsetAt());
    }
}
