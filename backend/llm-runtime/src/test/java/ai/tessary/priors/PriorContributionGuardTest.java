// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The load-bearing trust property of the pooling boundary, proven at the type level: <b>raw tenant data cannot
 * cross the boundary</b>. {@link PriorContribution} is the only class that carries tenant-derived
 * numbers across the pooling boundary, and it is constructed-validated so an ill-formed (or
 * content-smuggling) contribution can never exist. These tests demonstrate the three-data-classes
 * rule mechanically: there is no field for raw content, the one optional {@code contentRef} is
 * structurally constrained to be a ref (not a payload), and the aggregate value is bounded so DP
 * sensitivity is well-defined.
 */
class PriorContributionGuardTest {

    private static PriorContribution valid(@Nullable String ref) {
        return new PriorContribution("c1", "org1", "grader.pass_rate", 0.5, 100, ref);
    }

    @Test
    void acceptsAWellFormedAggregateOnlyContribution() {
        assertDoesNotThrow(() -> valid("entry:abc123")); // a short, single-line provenance pin
        assertDoesNotThrow(() -> valid(null)); // ref is optional
    }

    @Test
    void rejectsAFreeTextFeatureKey_soBucketsAreNamesNotContent() {
        // A raw span body or prompt would never match namespace.metric.
        assertThrows(
                IllegalArgumentException.class,
                () -> new PriorContribution("c1", "org1", "The user asked about refunds...", 0.5, 10, null));
    }

    @Test
    void rejectsAnUnboundedValue_soDpSensitivityStaysWellDefined() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PriorContribution("c1", "org1", "grader.pass_rate", 1.5, 10, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PriorContribution("c1", "org1", "grader.pass_rate", -0.1, 10, null));
    }

    @Test
    void rejectsContentSmuggledThroughTheRefField() {
        // A multi-line blob is content, not a ref — rejected.
        assertThrows(
                IllegalArgumentException.class, () -> valid("line one of a raw trace\nline two: the model said..."));

        // A long blob (a serialized span) is content, not a ref — rejected by the length bound.
        String blob = "x".repeat(PriorContribution.MAX_REF_LEN + 1);
        assertThrows(IllegalArgumentException.class, () -> valid(blob));
    }

    @Test
    void rejectsAZeroSampleCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PriorContribution("c1", "org1", "grader.pass_rate", 0.5, 0, null));
    }

    @Test
    void hasNoFieldThatCanHoldRawContent() {
        // Structural assertion: the record's only String components are the orgId/contributionId/
        // featureKey/contentRef identifiers — there is no free-form content field at all (no
        // "body", "text", "payload", "content" other than the bounded provenance "contentRef").
        // The absence of such a field is the proof that raw data cannot cross.
        java.util.Set<String> components = java.util.Arrays.stream(PriorContribution.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(
                java.util.Set.of("contributionId", "orgId", "featureKey", "value", "sampleCount", "contentRef"),
                components,
                "PriorContribution must carry only aggregate/identifier fields — no raw-content field");
    }
}
