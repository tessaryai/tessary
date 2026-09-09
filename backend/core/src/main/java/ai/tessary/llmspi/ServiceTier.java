// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llmspi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How a Bedrock request is served, and therefore what it costs. Bedrock offers Standard
 * (pay-per-token, the default), Priority (a premium for lower latency), Flex (a discount for
 * latency-tolerant work) and Reserved (a term commitment); separately, the <i>batch</i> APIs price
 * at a discount for asynchronous bulk work.
 *
 * <p><b>Why this is our own enum rather than a passthrough of the provider's own tier enum:</b> the
 * two concepts do not line up. {@link #BATCH} is not a {@code serviceTier} value at all — batch is a
 * separate API ({@code CreateModelInvocationJob}) — so it has <b>no wire form</b> and can never ride
 * on an online Converse request. Modelling that as a null {@link #wire()} lets the settings
 * validator and {@link ChatModelFactory} reject it at the boundary, instead of discovering it as a
 * Bedrock 400. Conversely we need a price factor for batch even though we never send it, because
 * the cost flows must be able to price a batch call.
 *
 * <p>Which tiers a given model actually supports is per-model and NOT uniform — every Claude model on
 * Bedrock is Standard only (no Flex, no Priority), and so is the GPT-5.6 line on bedrock-mantle. (Amazon
 * Nova 2 Lite, which supported all three, was the one model that offered Flex/Priority — it left the
 * platform's offered set with #939 D6's maker filter, since Amazon is not a supported maker; the tiers
 * themselves stay real Bedrock concepts, just currently unoffered.) That matrix lives in
 * {@link BedrockModelProfile}; this enum is just the vocabulary.
 */
public enum ServiceTier {

    /** Pay-per-token on-demand, no commitment. Bedrock's default when {@code serviceTier} is absent. */
    STANDARD("standard", new BigDecimal("1.0"), true),

    /**
     * Latency-tolerant work at a discount. AWS prices Flex at 50% of the Standard tier
     * ("Flex tier and Batch pricing is at 50% discount to Standard tier pricing"), which is why the
     * factor is derived here rather than transcribed as a second rate table — a derived factor cannot
     * drift out of sync with the Standard rate above it the way a duplicated table would.
     */
    FLEX("flex", new BigDecimal("0.5"), true),

    /**
     * Lower latency at a premium over Standard. Selectable on the wire, but deliberately carries
     * <b>no price factor</b>: the premium is model-specific and we have no published multiplier to
     * derive it from, so pricing a Priority call would be a guess. Until real rates are entered per
     * model, {@code pricing/PlatformCallPricer} emits no cost for it rather than a wrong one.
     */
    PRIORITY("priority", null, true),

    /**
     * Asynchronous bulk inference, priced at 50% of Standard like Flex. Present so the cost flows
     * can price a batch call; <b>no wire form</b>, because batch is a separate API and not a
     * {@code serviceTier} value (see the class javadoc). Nothing in the app submits batch jobs yet.
     */
    BATCH("batch", new BigDecimal("0.5"), false);

    private final String wireName;
    private final @Nullable BigDecimal priceFactor;

    /**
     * Whether this tier is selectable on an online chat request.
     *
     * <p>A plain boolean now that the provider's own enum has moved out ({@code llm.BedrockTiers}):
     * "can be sent online" is a platform fact — batch is a different API entirely — and it was only
     * ever inferred from whether a provider constant happened to be non-null.
     */
    private final boolean online;

    ServiceTier(String wireName, @Nullable BigDecimal priceFactor, boolean online) {
        this.wireName = wireName;
        this.priceFactor = priceFactor;
        this.online = online;
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

    /**
     * Multiplier applied to the model's Standard rates to price this tier, or null when we have no
     * published factor ({@link #PRIORITY}). Applied by {@code pricing/PlatformCallPricer.price}.
     */
    public @Nullable BigDecimal priceFactor() {
        return priceFactor;
    }

    /** Whether this tier can be sent on an online chat request (i.e. has a wire form). */
    public boolean isOnline() {
        return online;
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
