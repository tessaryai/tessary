// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

/**
 * One {@code pii_redaction_rule} row, applied on the write path before persistence. {@code builtIn} rules may be
 * disabled but not edited or deleted. Rules apply lowest {@code sortOrder} first, so a broad rule can follow a specific
 * one.
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
