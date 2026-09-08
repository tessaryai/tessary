// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for call-site resolution at the substrate edge: explicit tag only, no fallback. */
class CallSiteResolverTest {

    @Test
    void explicitTag_isAuthoritative() {
        Map<String, Object> meta = Map.of(GenAiAttributes.TESSARY_CALL_SITE_ID, "explicit_site");
        assertEquals("explicit_site", CallSiteResolver.resolve(meta));
    }

    @Test
    void explicitTag_onPlainOtlp_resolves() {
        // OTLP-first: a plain OTLP producer sets tessary.call_site.id by hand — no SDK required.
        Map<String, Object> meta =
                Map.of(GenAiAttributes.TESSARY_CALL_SITE_ID, "explicit_site", GenAiAttributes.TESSARY_SDK, "python");
        assertEquals("explicit_site", CallSiteResolver.resolve(meta));
    }

    @Test
    void aliasSpelling_isNotRead() {
        // Ingestion-contract: only the canonical dotted spelling resolves — no alias fallback.
        assertNull(CallSiteResolver.resolve(Map.of("tessary.call_site_id", "underscore_site")));
    }

    @Test
    void untagged_doesNotResolve_noCodeFilepathFallback() {
        // There is no code.filepath fallback: an untagged span is unassigned (null), regardless of any
        // code.filepath attribute or provenance marker it carries.
        assertNull(CallSiteResolver.resolve(Map.of("code.filepath", "/app/src/checkout.py")));
        assertNull(CallSiteResolver.resolve(Map.of(GenAiAttributes.TESSARY_SDK, "python")));
    }

    @Test
    void blankTag_returnsNull() {
        assertNull(CallSiteResolver.resolve(Map.of(GenAiAttributes.TESSARY_CALL_SITE_ID, "")));
    }

    @Test
    void nullMetadata_returnsNull() {
        assertNull(CallSiteResolver.resolve(null));
    }
}
