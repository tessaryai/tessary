// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for trace search over span payloads ({@code tessary.traces.search.*}). Below {@link #walkThreshold} matches
 * the payload index lists every match; at it, walking the newest traces finds a page sooner. {@link #timeoutMs} makes a
 * too-broad search fail clearly instead of holding a connection.
 */
@ConfigurationProperties(prefix = "tessary.traces.search")
public record TraceSearchProperties(

        /**
         * At 50,000, listing every match costs a few hundred ms, while the walk is slow when a common word's matches
         * are all old.
         */
        @DefaultValue("50000") int walkThreshold,
        @DefaultValue("5000") long timeoutMs) {}
