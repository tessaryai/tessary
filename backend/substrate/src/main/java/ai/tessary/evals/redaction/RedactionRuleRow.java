// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.redaction;

/**
 * One persisted PII redaction rule, a row of {@code pii_redaction_rule}.
 * A rule is a named regex {@code pattern} whose matches are replaced by {@code replacement} on the
 * substrate write path before persistence, so no unredacted PII reaches the agent-native substrate.
 *
 * <p>{@code builtIn} marks the platform-seeded starter rules (email/phone/SSN/credit-card) a project may
 * disable but not edit or delete. {@code sortOrder} makes redaction deterministic — rules apply
 * lowest-first, so a project can layer a broad rule after a specific one.
 */
public record RedactionRuleRow(
        String id,
        String projectId,
        String name,
        String pattern,
        String replacement,
        boolean enabled,
        boolean builtIn,
        int sortOrder,
        String createdAt,
        String updatedAt) {}
