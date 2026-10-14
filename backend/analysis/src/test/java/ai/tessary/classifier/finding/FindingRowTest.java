// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The row's projections. */
class FindingRowTest {

    /**
     * The claim an adapter and RCA read is the row's own values, field for field. The bug is two same-typed
     * arguments swapped in {@code claim()}: every field here has a value no other field has.
     */
    @Test
    void theClaimCarriesTheRowsOwnValues() {
        FindingRow row = FindingRowBuilder.of("custom-x")
                .id("f1")
                .projectId("p1")
                .causeKey("c1")
                .subject(FindingRow.SubjectKind.CLASSIFIER, "s1")
                .subjectLabel("L")
                .callSiteId("cs")
                .onsetAt("2026-10-01T00:00:00Z")
                .lastSeenAt("2026-10-02T00:00:00Z")
                .title("T")
                .basis("B")
                .severity(0.4)
                .sampleCount(7)
                .payload("{\"version_id\":\"v1\"}")
                .evidenceCounts("{\"witness\":3}")
                .createdAt("2026-10-01T00:00:01Z")
                .build();

        assertEquals(
                new FindingClaim(
                        "f1",
                        "p1",
                        "custom-x",
                        "c1",
                        FindingRow.SubjectKind.CLASSIFIER,
                        "s1",
                        "L",
                        "cs",
                        "2026-10-01T00:00:00Z",
                        "2026-10-02T00:00:00Z",
                        "T",
                        "B",
                        0.4,
                        7,
                        "{\"version_id\":\"v1\"}",
                        "{\"witness\":3}",
                        "2026-10-01T00:00:01Z"),
                row.claim());
    }
}
