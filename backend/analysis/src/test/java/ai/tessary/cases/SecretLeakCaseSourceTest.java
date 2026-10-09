// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingRowBuilder;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A secret-leak finding shaped into the case that says which credential to rotate. The detail service's
 * repositories are left unstubbed: they answer "no detection rows", which is the degraded read these tests
 * are about.
 */
@ExtendWith(MockitoExtension.class)
class SecretLeakCaseSourceTest {

    @Mock
    ClassifierDetectionWriteRepository detections;

    @Mock
    FindingEvidenceRepository evidence;

    /**
     * A secret-leak finding that is not an armed-window facet has no leak detail to read. Opening a case for
     * it anyway would file a credential incident with no rule and no count, so the open is refused by name.
     */
    @Test
    void aFindingWithNoLeakDetailIsRefusedRatherThanOpenedEmpty() {
        var finding = FindingRowBuilder.of(BuiltInDetector.Kind.SECRET_LEAK).build();

        TessaryException e = assertThrows(TessaryException.class, () -> source().shape(finding));

        assertEquals(ClassifierError.FINDING_NOT_FOUND, e.error());
    }

    /**
     * With no detection summary the leak count falls back to the finding's own sample count, and an onset
     * in Postgres's rendering leaves the case unbracketed instead of failing the open of a live leak.
     */
    @Test
    void aLeakWithAnUnparseableOnsetStillOpensItsCaseAtTheTopOfTheList() {
        var finding = FindingRowBuilder.of(BuiltInDetector.Kind.SECRET_LEAK)
                .causeKey("sig-1:aws-access-key-id:checkout-agent")
                .callSiteId("checkout-agent")
                .onsetAt("2026-09-01 00:00:00+00")
                .sampleCount(3)
                .payload("{\"cause_kind\":\"armed_window\",\"native_cause_key\":\"aws-access-key-id\"}")
                .build();

        assertEquals(
                new CaseDetection(
                        new CaseKey(
                                CaseRow.Detector.SECRET_LEAK,
                                CaseRow.SubjectKind.SECRET_PATTERN,
                                "sig-1:aws-access-key-id:checkout-agent",
                                "leak_count"),
                        "aws-access-key-id",
                        "checkout-agent",
                        "fnd-1",
                        "aws-access-key-id",
                        "3 outputs matched the aws-access-key-id rule at high confidence, from 1 call site. A single"
                                + " high-confidence match opens the finding.",
                        1.0,
                        null,
                        3.0,
                        null,
                        null),
                source().shape(finding));
    }

    private SecretLeakCaseSource source() {
        return new SecretLeakCaseSource(new SecretLeakDetailService(detections, evidence));
    }
}
