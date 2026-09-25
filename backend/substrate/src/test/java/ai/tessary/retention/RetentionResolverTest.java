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
 * The retention ceiling another build supplies: every effective retention is clamped to it, so the sweep
 * and the settings page never promise to keep data longer than the ceiling allows.
 */
@ExtendWith(MockitoExtension.class)
class RetentionResolverTest {

    private static final int CEILING = 30;

    @Mock
    RetentionPolicyRepository policies;

    @ParameterizedTest(name = "override {0}, platform default {1} -> {2} days")
    @CsvSource(
            nullValues = "null",
            value = {
                "90,   14, 30", // an override above the ceiling is cut to it
                "0,    14, 30", // "keep forever" becomes the ceiling, never unbounded
                "7,    14, 7", // an override inside the ceiling stands
                "null, 60, 30", // the platform default is clamped too
                "null, 0,  30", // an unbounded platform default is clamped too
            })
    void everyTraceRetentionIsClampedToTheCeiling(@Nullable Integer override, int platformDefault, int expected) {
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
        RetentionResolver resolver = new RetentionResolver(policies, props, (projectId, dataClass) -> CEILING);

        assertEquals(
                new EffectiveRetention(DataClass.TRACES, expected, override != null),
                resolver.resolve("p-1").get(0));
    }
}
