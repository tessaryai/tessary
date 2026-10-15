// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import java.time.Instant;

/**
 * Why a provider-calling classifier stopped, and since when. A paused sweep sends nothing and advances past what it
 * skipped; after {@code tessary.frustration.credential-retry-seconds} the next sweep checks the key again.
 */
public record ClassifierPause(String reason, Instant pausedAt) {

    public static final String PROVIDER_REJECTED = "provider_rejected";

    /** Every later turn is the same request, so the sweep stops and the log carries the provider's words. */
    public static final String REQUEST_REFUSED = "request_refused";

    public static final String NO_PROVIDER = "no_provider";

    /** The org has no credit left on the provider the lane runs on: its own key answered 402. */
    public static final String NO_CREDIT = "no_credit";
}
