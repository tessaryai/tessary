// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.catalog.BuiltInDetector;
import org.junit.jupiter.api.Test;

/**
 * A finding's payload and evidence counts read back through the row. A blob that does not parse must read
 * as empty rather than throw: every reader of the finding (its title, its case, its page) goes through here,
 * and one bad blob would otherwise take the whole findings list down with it.
 */
class FindingPayloadTest {

    @Test
    void anUnparseableBlobReadsAsEmpty() {
        FindingRow row = FindingRowBuilder.of(BuiltInDetector.Kind.REGEX)
                .causeKey("sig-1:refusal-spike")
                .payload("{not json")
                .build();

        assertEquals(0, row.payload().size());
        assertNull(row.payloadText("native_cause_key"));
        assertEquals("sig-1:refusal-spike", row.nativeCauseKey(), "the cause key stands in for the unreadable one");
        assertEquals(0, row.evidenceCount(FindingEvidenceRow.Role.WITNESS));
    }
}
