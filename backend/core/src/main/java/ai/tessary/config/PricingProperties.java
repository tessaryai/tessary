// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The pricing switch ({@code tessary.pricing.*}) for the bundled and fetched price books. Rates are deliberately not
 * configurable: they are checked-in files, so a price change is a reviewed diff.
 */
@ConfigurationProperties(prefix = "tessary.pricing")
public record PricingProperties(

        /** Off means every model prices as unpriced. */
        @DefaultValue("true") boolean enabled) {}
