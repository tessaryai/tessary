// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import ai.tessary.config.RetentionProperties;
import ai.tessary.ops.RetentionPolicyRepository;
import ai.tessary.ops.RetentionPolicyRow;
import ai.tessary.retention.RetentionResolver.DataClass;
import ai.tessary.retention.RetentionResolver.EffectiveRetention;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A retention another build fixes replaces both the platform default and the project's own override, so
 * the sweep and the settings page report the fixed number whatever the project saved before.
 */
@ExtendWith(MockitoExtension.class)
class RetentionResolverTest {

    private static final int FIXED = 21;

    @Mock
    RetentionPolicyRepository policies;

    @ParameterizedTest(name = "override {0}, platform default {1}, fixed {2} -> {3} days")
    @CsvSource(
            nullValues = "null",
            value = {
                "90,   14, 0,     90", // nothing fixed: the override stands
                "null, 14, 0,     14", // nothing fixed, no override: the platform default applies
                "90,   14, FIXED, 21", // a longer override is replaced
                "7,    14, FIXED, 21", // a shorter override is replaced too
                "0,    14, FIXED, 21", // "keep forever" is replaced, never unbounded
                "null, 0,  FIXED, 21", // an unbounded platform default is replaced
            })
    void aFixedRetentionReplacesTheDefaultAndTheOverride(
            @Nullable Integer override, int platformDefault, String fixed, int expected) {
        int fixedDays = fixed.equals("FIXED") ? FIXED : Integer.parseInt(fixed);
        RetentionProperties props = new RetentionProperties();
        props.setTraceTtlDays(platformDefault);
        props.setDetectionTtlDays(10);
        when(policies.listByProject("p-1"))
                .thenReturn(
                        override == null
                                ? List.of()
                                : List.of(new RetentionPolicyRow(
                                        "r-1",
                                        "p-1",
                                        DataClass.TRACES.wire(),
                                        override,
                                        null,
                                        "2026-09-01T00:00:00Z",
                                        "{}")));
        RetentionResolver resolver = new RetentionResolver(policies, props, (projectId, dataClass) -> fixedDays);

        assertEquals(
                new EffectiveRetention(DataClass.TRACES, expected, override != null && fixedDays == 0),
                resolver.resolve("p-1").get(0));
    }
}
