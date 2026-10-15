// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

/**
 * A case's identity. Matches {@code ux_eval_case_live} and the subject key {@code rca_report} carries, so there is one
 * way of naming a degrading thing.
 */
public record CaseKey(String detector, String subjectKind, String subjectId, String metric) {}
