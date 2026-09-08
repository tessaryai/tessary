// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

/**
 * A case's identity: what noticed, what it is about, and in which lane. Matches
 * {@code ux_eval_case_live} exactly, and deliberately matches the subject key {@code rca_report}
 * already carries — the platform has exactly one way of naming a degrading thing, and this is it.
 */
public record CaseKey(String detector, String subjectKind, String subjectId, String metric) {}
