// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import org.jspecify.annotations.Nullable;

/**
 * The counts of one route over the learn window: its traces, those with no user ID, with input, with a parent, starting
 * on second 0 and on a minute divisible by 5, and the reply delays (seconds from the latest end of an earlier trace of
 * the same session, 0 to 1800) in ascending order.
 */
public record RouteTally(
        @Nullable String traceName,
        String callSiteId,
        int traces,
        int nullIds,
        int withInput,
        int withParent,
        int second0,
        int minute5,
        double[] delays) {}
