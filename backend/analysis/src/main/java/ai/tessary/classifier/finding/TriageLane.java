// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

/**
 * Which instrument ruled on a finding; one is left. {@link #EVIDENCE_ONLY} rules on the evidence alone and cannot say
 * why anything happened. Kept as an enum because {@link #wire()} is on the wire. Not {@code ModelLane.TRIAGE}, which
 * names a model choice.
 */
public enum TriageLane {

    /** One microVM per finding, a two-file dossier, read-only MCP, and {@code checks/} for scripts. */
    EVIDENCE_ONLY("evidence_only");

    private final String wire;

    TriageLane(String wire) {
        this.wire = wire;
    }

    /** Not persisted on the finding. */
    public String wire() {
        return wire;
    }
}
