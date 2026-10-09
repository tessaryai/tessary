// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

/** One version of the rate table: a {@code price_book} row. */
public record PriceBook(String version) {

    /** The vendored LiteLLM snapshot — broad coverage, community-maintained, occasionally wrong. */
    public static final String SOURCE_LITELLM = "litellm";
}
