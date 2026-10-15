// SPDX-License-Identifier: Apache-2.0
package ai.tessary.onboarding;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The onboarding ladder, in order. Always the furthest rung reached, so progress never walks backwards on a quiet week.
 * {@link #FITTING} names the days-long wait honestly.
 */
public enum OnboardingStage {

    /** Nothing could arrive even if it were sent. */
    NOT_CONNECTED("not_connected"),

    /** A key exists and the endpoint is live; no span has arrived yet. */
    LISTENING("listening"),

    /** No bucket has enough comparable samples on both sides yet. */
    FITTING("fitting"),

    /** Nothing has moved past its floor: the healthy steady state. */
    WATCHING("watching"),

    /** Not yet ruled on. */
    FINDING("finding"),

    /**
     * A triaged case. A case a human opened is deliberately not this: the milestone measures whether the product got
     * here unaided.
     */
    CASE("case");

    private final String wire;

    OnboardingStage(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
