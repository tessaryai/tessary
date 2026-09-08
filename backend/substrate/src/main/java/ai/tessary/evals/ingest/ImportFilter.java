// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Per-run filter applied to source.fetch().
 * Provider adapters honor whichever fields apply; unset fields impose no constraint.
 */
public record ImportFilter(
        @Nullable Integer lookbackHours,
        @Nullable String tag,
        @Nullable String projectId,
        @Nullable String name,
        @Nullable String model,
        @Nullable Integer limit,
        /** ISO-8601 lower bound on trace start time (inclusive); null imposes no lower bound.
         *  An explicit range takes precedence over {@link #lookbackHours}. */
        @Nullable String fromTimestamp,
        /** ISO-8601 upper bound on trace start time; null imposes no upper bound. */
        @Nullable String toTimestamp,
        /** Glob patterns ({@code *} / {@code ?}) matched client-side against a span's name;
         *  any match keeps the span. Empty/null imposes no name constraint. Used by live datasets. */
        @Nullable List<String> namePatterns,
        /** Deployment environment (e.g. "production"). Applied server-side by providers that
         *  support it (Langfuse); ignored elsewhere. Null imposes no constraint. */
        @Nullable String environment) {
    /** A filter with no constraints. */
    public static ImportFilter empty() {
        return new ImportFilter(null, null, null, null, null, null, null, null, null, null);
    }
}
