// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import ai.tessary.model.ContentExtractor;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A substrate span flattened for signal evaluation. The turn is the trace, so callers group by {@link #traceId()}.
 * {@code sessionId} is null when the producer sent none; nothing is synthesized. Text detectors must read
 * {@link #inputText()}, {@link #outputText()} or {@link #groundingPremiseText()}, never the raw envelope columns.
 */
public record SubstrateObservation(
        String observationId,
        String projectId,
        String traceId,
        @Nullable String sessionId,
        @Nullable String projectVersionId,
        @Nullable String callSiteId,
        @Nullable String kind,
        @Nullable String name,
        @Nullable String input,
        @Nullable String output,
        @Nullable String toolError,
        String createdAt,
        /** A JSON array of rule, field and anchored; null when nothing was removed or the payload aged out. */
        @Nullable String redactionsJson) {

    public String inputText() {
        return ContentExtractor.columnText(input, "user");
    }

    /** System and user text, since source content rides in either; the frustration thread excludes system. */
    public String groundingPremiseText() {
        return ContentExtractor.columnTextForRoles(input, Set.of("system", "user"));
    }

    public String outputText() {
        return ContentExtractor.columnText(output, "assistant");
    }
}
