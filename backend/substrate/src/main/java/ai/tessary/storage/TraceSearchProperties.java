// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the traces search over span inputs and outputs, bound from {@code tessary.traces.search.*}.
 *
 * <p>{@link #walkThreshold} picks the query shape. A search counts its matching payloads in the project, stopping at
 * this number: below it, the payload index lists every match; at it, the word is common enough that walking the newest
 * traces finds a page sooner than listing every match would. {@link #timeoutMs} bounds every read that carries a
 * search, so a search that is too broad for either shape fails with a clear error instead of holding a connection.
 */
@ConfigurationProperties(prefix = "tessary.traces.search")
public record TraceSearchProperties(

        /**
         * 50,000 keeps a project of that many spans on the index for every word: listing all its matches costs a few
         * hundred milliseconds there, while the walk is slow when a common word's matches are all old. Above it, a word in
         * that many payloads is dense enough that the walk reaches a page sooner.
         */
        @DefaultValue("50000") int walkThreshold,
        @DefaultValue("5000") long timeoutMs) {}
