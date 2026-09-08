// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the call site an ingested observation belongs to, at the substrate edge, so production
 * telemetry is correlatable to the call-site-keyed grading / observer machinery.
 *
 * <p>OTLP-first, <b>explicit-tag only</b>: the call site is bound by the {@code tessary.call_site.id} span
 * attribute (the dotted canonical, rooting the expandable {@code tessary.call_site.*} namespace — the only
 * spelling read), set by {@code observe(call_site=…)} / {@code span(call_site=…)} or by any OTLP producer
 * by hand. Trusted as-is — the call site is named explicitly at the source, so it survives refactors and
 * never mis-attributes. An untagged span resolves to {@code null} (unassigned); the resolver never guesses:
 * there is no fuzzy file matching and no structural fallback (see
 * docs/reference/ingestion-contract/README.md).
 */
public final class CallSiteResolver {

    private CallSiteResolver() {}

    /**
     * @param metadata the observation's flattened span attributes (carries {@code tessary.call_site.id} when
     *     present)
     * @return the resolved call-site id, or {@code null} when the span carries no explicit tag
     */
    public static @Nullable String resolve(@Nullable Map<String, ? extends @Nullable Object> metadata) {
        if (metadata == null) {
            return null;
        }
        // Explicit tag: the canonical dotted tessary.call_site.id only. Authoritative — the call site
        // is named explicitly at the source.
        Object tagged = metadata.get(GenAiAttributes.TESSARY_CALL_SITE_ID);
        if (tagged != null && !tagged.toString().isBlank()) {
            return tagged.toString();
        }
        return null;
    }
}
