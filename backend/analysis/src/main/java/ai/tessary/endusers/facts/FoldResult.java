// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

/** What one fold run wrote, summed over its days. */
public record FoldResult(int days, int traces, int idDays, int aliasEdges, int users, int gapDays, int outageDays) {}
