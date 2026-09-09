// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How far a project has got from "nothing" to "a case opened" — the ladder launch requirement G3 names,
 * in order.
 *
 * <p>It exists as an ordered enum rather than a bag of booleans because the surface has to say ONE thing
 * at a time ("we are fitting baselines") and because time-to-first-value is measured against transitions
 * between these (G6). The stage is always the furthest rung reached, never the highest one true: a
 * project that has a case has obviously also had a trace, and reporting the ladder non-monotonically
 * would make the progress bar walk backwards on a quiet week.
 *
 * <p><b>The interesting rung is {@link #FITTING}.</b> That is the days-long wait risk 3 calls out — a new
 * project sees nothing while the detectors accumulate comparable samples — and naming it as a stage is
 * what lets the product say so honestly instead of rendering an empty screen (G4).
 */
public enum OnboardingStage {

    /** No ingest key has been minted. Nothing could arrive even if it were sent. */
    NOT_CONNECTED("not_connected"),

    /** A key exists and the endpoint is live; no span has arrived yet. */
    LISTENING("listening"),

    /**
     * Traffic is arriving and the detectors are accumulating windows, but no bucket has enough
     * comparable samples on both sides to make a claim. The honest wait.
     */
    FITTING("fitting"),

    /** At least one bucket can compare. Nothing has moved past its floor yet — the healthy steady state. */
    WATCHING("watching"),

    /** A detector has written a finding. It has not been ruled on. */
    FINDING("finding"),

    /**
     * An triaged case exists — first value, per decision D9. A case a human opened by pressing
     * <i>Real deviation</i> is a real case and is deliberately not this: the milestone measures whether
     * the product got here unaided.
     */
    CASE("case");

    private final String wire;

    OnboardingStage(String wire) {
        this.wire = wire;
    }

    /** The stable snake_case wire value the SPA switches on. */
    @JsonValue
    public String wire() {
        return wire;
    }
}
