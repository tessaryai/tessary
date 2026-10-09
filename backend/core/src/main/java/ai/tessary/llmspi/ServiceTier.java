// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * How a Bedrock request is served. Bedrock offers Standard (pay-per-token, the default), Priority (a
 * premium for lower latency), Flex (a discount for latency-tolerant work) and Reserved (a term
 * commitment); separately, the <i>batch</i> APIs serve asynchronous bulk work at a discount.
 *
 * <p><b>Why this is our own enum rather than a passthrough of the provider's own tier enum:</b> the
 * two concepts do not line up. {@link #BATCH} is not a {@code serviceTier} value at all — batch is a
 * separate API ({@code CreateModelInvocationJob}) — so it can never ride on an online Converse
 * request.
 *
 * <p>Every model the platform offers today runs at Standard; the other tiers stay here as the
 * vocabulary the {@code project_model_setting.service_tier} and {@code llm_call.service_tier} columns
 * are written in.
 */
public enum ServiceTier {

    /** Pay-per-token on-demand, no commitment. Bedrock's default when {@code serviceTier} is absent. */
    STANDARD("standard"),

    /** Latency-tolerant work at a discount. */
    FLEX("flex"),

    /** Lower latency at a premium over Standard. */
    PRIORITY("priority"),

    /**
     * Asynchronous bulk inference. Batch is a separate API and not a {@code serviceTier} value (see the
     * class javadoc). Nothing in the app submits batch jobs yet.
     */
    BATCH("batch");

    private final String wireName;

    ServiceTier(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The lowercase wire/DB identifier ({@code standard}, {@code flex}, …). {@code @JsonValue} keeps
     * the JSON form identical to the {@code project_model_setting.service_tier} column, so the wire,
     * the DB and this enum never drift into three spellings of the same thing.
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** Resolve a stored/wire value, case-insensitively; empty-safe. Unknown values throw. */
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
