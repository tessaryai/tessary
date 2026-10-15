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

    /** The org's own key answered 402, or the deployment's provider has no credit left for the org. */
    public static final String NO_CREDIT = "no_credit";

    /** Not the org's to fix: lifts when a later call goes through. */
    public static final String PLATFORM_UNAVAILABLE = "platform_unavailable";
}
