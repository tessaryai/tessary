// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.substrate.ActionSymbol;
import org.jspecify.annotations.Nullable;

/** Design contract: {@code devdocs/concepts/tool-error.md} §3. */
public final class ToolErrorBuckets {

    private ToolErrorBuckets() {}

    /** Persisted in {@code metric_baseline.bucket_kind}; never renamed. */
    public static final String KIND = "tool";

    /**
     * The same alphabet {@code tool_duration} uses, so both findings name the same tool and share normalization, which
     * keeps buckets thick enough to have a rate.
     */
    public static String toolKey(@Nullable String toolName) {
        return ActionSymbol.of(KIND, toolName);
    }
}
