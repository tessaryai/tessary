// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How a Bedrock request is served. Our own enum because {@link #BATCH} is a separate API, not a {@code serviceTier}
 * value. Everything runs at Standard today; the rest is the vocabulary of the {@code service_tier} columns.
 */
public enum ServiceTier {

    /** Bedrock's default when {@code serviceTier} is absent. */
    STANDARD("standard"),

    FLEX("flex"),

    PRIORITY("priority"),

    /** Not a {@code serviceTier} value; nothing submits batch jobs yet. */
    BATCH("batch");

    private final String wireName;

    ServiceTier(String wireName) {
        this.wireName = wireName;
    }

    /** {@code @JsonValue} keeps the wire, DB and enum on one spelling. */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** Case-insensitive; unknown values throw. */
    @JsonCreator
    public static ServiceTier fromWire(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ServiceTier t : values()) {
                if (t.wireName.equals(normalized)) return t;
            }
        }
        throw new IllegalArgumentException("unknown service tier: " + value);
    }
}
