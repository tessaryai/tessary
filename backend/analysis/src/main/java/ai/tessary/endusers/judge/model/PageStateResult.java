// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The page state of spec 3.5 after the two-run memory: a refusal applies at once, and a looser state applies only after
 * two runs in a row gave it.
 *
 * @param state the state the page shows
 * @param ruleState the state the rules gave before the memory; this is the state the next run remembers
 * @param reasonNumbers the numbers the frontend puts in the reason sentence, by snake_case name
 * @param groupBanner group-hint and aggregate IDs hold 5% or more of the ID-days
 * @param namedIds key indexes of up to 10 heavy IDs a refusal names, heaviest first
 */
public record PageStateResult(
        PageState state,
        PageState ruleState,
        @Nullable PageStateReason reason,
        Map<String, Double> reasonNumbers,
        boolean groupBanner,
        List<Integer> namedIds) {}
