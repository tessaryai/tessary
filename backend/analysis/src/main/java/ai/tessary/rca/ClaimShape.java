// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.FindingRow;

/** What a finding claims, which decides what its flagged rows are compared against. */
public enum ClaimShape {
    RATE_RISE("rate rise"),
    DISTRIBUTION_SHIFT("distribution shift"),
    PRESENCE_EVENT("presence event");

    final String label;

    ClaimShape(String label) {
        this.label = label;
    }

    /**
     * A kind this code does not know, such as a custom classifier's own or the classifier key it falls back to, is a
     * rate rise: a share of flagged rows is the one claim every classifier can make.
     */
    public static ClaimShape of(String causeKind) {
        return switch (causeKind) {
            case FindingRow.Cause.DISTRIBUTION_SHIFT -> DISTRIBUTION_SHIFT;
            case FindingRow.Cause.ARMED_WINDOW -> PRESENCE_EVENT;
            default -> RATE_RISE;
        };
    }
}
