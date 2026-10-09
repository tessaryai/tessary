// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Call-site resolution at the substrate edge: the explicit tag only, with no fallback. */
class CallSiteResolverTest {

    static Stream<Arguments> metadata() {
        return Stream.of(
                // OTLP-first: a plain OTLP producer sets tessary.call_site.id by hand, no SDK required.
                Arguments.of(
                        Map.of(GenAiAttributes.TESSARY_CALL_SITE_ID, "explicit_site", "tessary.sdk", "python"),
                        "explicit_site"),
                // Only the canonical dotted spelling resolves.
                Arguments.of(Map.of("tessary.call_site_id", "underscore_site"), null),
                // No code.filepath fallback: an untagged span is unassigned.
                Arguments.of(Map.of("code.filepath", "/app/src/checkout.py"), null),
                Arguments.of(Map.of("tessary.sdk", "python"), null),
                Arguments.of(Map.of(GenAiAttributes.TESSARY_CALL_SITE_ID, ""), null),
                Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("metadata")
    void resolvesOnlyAnExplicitCallSiteTag(@Nullable Map<String, Object> meta, @Nullable String callSite) {
        assertEquals(callSite, CallSiteResolver.resolve(meta));
    }
}
