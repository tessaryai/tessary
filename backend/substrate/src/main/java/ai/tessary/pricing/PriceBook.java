// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

public record PriceBook(String version) {

    /** Broad coverage, community-maintained, occasionally wrong. */
    public static final String SOURCE_LITELLM = "litellm";
}
