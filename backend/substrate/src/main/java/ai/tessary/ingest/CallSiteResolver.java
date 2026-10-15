// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves an observation's call site from the explicit {@code tessary.call_site.id} span attribute only. An untagged
 * span resolves to null; there is no fuzzy or structural fallback, so it never mis-attributes (see
 * devdocs/reference/ingestion-contract/README.md).
 */
public final class CallSiteResolver {

    private CallSiteResolver() {}

    /** @return null when the span carries no explicit tag */
    public static @Nullable String resolve(@Nullable Map<String, ? extends @Nullable Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object tagged = metadata.get(GenAiAttributes.TESSARY_CALL_SITE_ID);
        if (tagged != null && !tagged.toString().isBlank()) {
            return tagged.toString();
        }
        return null;
    }
}
