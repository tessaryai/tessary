// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

/**
 * The seven level 2 guards, one for each {@code tessary.end-users.guards.*} install key. A guard that is off removes
 * only that guard; the v3 rule behind it stays.
 */
public record Guards(
        boolean notJudgedWithoutPastCheck,
        boolean cannotTellYet,
        boolean historyTooShort,
        boolean hitsNotComplete,
        boolean mixedIdFormats,
        boolean findingsFireTogether,
        boolean outageBanner) {

    public static Guards allOn() {
        return new Guards(true, true, true, true, true, true, true);
    }
}
