// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.TracerVerdict;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The tracer test of spec 3.6.
 *
 * @param ended IDs that ended while holding a repeating job key, in the window used
 * @param continued of those, IDs whose job went on under a new ID
 * @param recentWindow the counts are of the last 56 days, not of all data
 * @param replacedLow lower end of the share of replaced IDs, for the stop range; null unless some IDs are replaced
 * @param bornAfterContinued IDs first seen on or after the first continued day, never new
 */
public record TracerResult(
        TracerVerdict verdict,
        int ended,
        int continued,
        boolean recentWindow,
        @Nullable Integer firstContinuedDay,
        @Nullable Double replacedLow,
        Set<Integer> bornAfterContinued) {}
