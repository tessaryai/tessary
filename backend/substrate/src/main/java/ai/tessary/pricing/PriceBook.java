// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import java.time.Instant;

/**
 * One version of the rate table: a {@code price_book} row.
 *
 * <p>{@code source} is where the rates came from. A hand-maintained {@code manual} book once
 * layered corrections over this one; every row it carried is now either reconciled upstream or a
 * genuine gap this platform prices as unpriced rather than guessed, so there is exactly one source.
 */
public record PriceBook(String version, String source, Instant publishedAt) {

    /** The vendored LiteLLM snapshot — broad coverage, community-maintained, occasionally wrong. */
    public static final String SOURCE_LITELLM = "litellm";
}
