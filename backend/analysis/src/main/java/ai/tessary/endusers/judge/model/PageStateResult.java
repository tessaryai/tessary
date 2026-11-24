// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The page state of spec 3.5 after the two-run memory.
 *
 * @param reasonNumbers the numbers the frontend puts in the reason sentence, by snake_case name
 * @param groupBanner group-hint and aggregate IDs hold 5% or more of the ID-days
 * @param namedIds key indexes of up to 10 heavy IDs a refusal names, heaviest first
 */
public record PageStateResult(
        PageState state,
        @Nullable PageStateReason reason,
        Map<String, Double> reasonNumbers,
        boolean groupBanner,
        List<Integer> namedIds) {}
