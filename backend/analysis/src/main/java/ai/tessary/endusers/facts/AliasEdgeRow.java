// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.endusers.model.AliasVia;

/**
 * One {@code end_user_alias_edge} row: a session or thread that holds exactly two user IDs, where the last trace of
 * {@code fromKey} is before the first trace of {@code toKey} (spec 3.2). Keys are lower-case hex.
 *
 * @param day the UTC day of the first trace of {@code toKey} in the session or thread
 */
public record AliasEdgeRow(String fromKey, String toKey, AliasVia via, int day) {}
