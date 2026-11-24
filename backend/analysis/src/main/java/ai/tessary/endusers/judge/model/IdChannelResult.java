// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * ID loss and ID change of spec 3.4.
 *
 * @param idLoss null when no slice lost its user IDs
 * @param idChange null when no event was found
 * @param censoredFrom key index to the day from which its silence is not read as a stop
 * @param cannotFollow key indexes that vanished in an ID change not solved by aliases
 * @param bornInChange key indexes born in an ID change, never new
 */
public record IdChannelResult(
        @Nullable IdLoss idLoss,
        @Nullable IdChange idChange,
        Map<Integer, Integer> censoredFrom,
        Set<Integer> cannotFollow,
        Set<Integer> bornInChange) {

    /**
     * @param slices the first six affected slices ({@code r|route}, {@code v|version}, {@code rv|route|version})
     * @param lostShare per ID in {@code V}: the share of its traffic on the affected slices
     * @param vanishedNormal {@code V0}, the IDs that vanish in a normal week over the same time
     * @param phi share of {@code V} that lost its ID rather than stopped
     */
    public record IdLoss(
            List<String> slices,
            int onsetDay,
            boolean provisional,
            double kappa,
            double phi,
            Map<Integer, Double> lostShare,
            double vanishedNormal) {}

    /** @param firstDay the first day of an event not solved by aliases; null when every event is solved */
    public record IdChange(
            List<IdChangeEvent> events, @Nullable Integer firstDay) {}

    public record IdChangeEvent(
            Scope scope,
            int day,
            double birthsExcess,
            double vanishExcess,
            double activeNormal,
            int linked,
            boolean solvedByAliases,
            List<Integer> vanished,
            List<Integer> born) {}
}
