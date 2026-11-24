// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

/**
 * What a hit rule is chosen by: the finding's classifier key and its cause kind ({@code payload.cause_kind}, else the
 * classifier key, as {@code FindingRow.causeKind()} reads it).
 *
 * @param faceted the finding is one facet of its classifier, such as one secret pattern at one call site
 */
public record HitFinding(String classifierKey, String causeKind, boolean faceted) {}
