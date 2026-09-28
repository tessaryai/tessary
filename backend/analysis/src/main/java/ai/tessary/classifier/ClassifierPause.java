// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import java.time.Instant;

/**
 * Why a classifier that calls a provider on the org's own key has stopped calling it, and since when.
 * A paused sweep sends nothing and advances past what it skipped; the next sweep past
 * {@code tessary.frustration.credential-retry-seconds} checks the key again.
 *
 * @param reason one of {@link #PROVIDER_REJECTED}, {@link #NO_PROVIDER}, {@link #NO_CREDIT} or
 *     {@link #PLATFORM_UNAVAILABLE}
 */
public record ClassifierPause(String reason, Instant pausedAt) {

    /** The provider answered 401 or 403 to the org's key. */
    public static final String PROVIDER_REJECTED = "provider_rejected";

    /** No key is configured for any provider the classifier's lane offers. */
    public static final String NO_PROVIDER = "no_provider";

    /** The lane runs on the deployment's own provider and the org has no credit left for it. */
    public static final String NO_CREDIT = "no_credit";

    /**
     * The deployment's own provider refused the deployment's key. Not the org's to fix: the pause lifts
     * when a later sweep's call goes through.
     */
    public static final String PLATFORM_UNAVAILABLE = "platform_unavailable";
}
