// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import ai.tessary.classifier.substrate.ActionSymbol;
import org.jspecify.annotations.Nullable;

/** How a tool call is keyed into a bucket. Design contract: {@code devdocs/concepts/tool-error.md} §3. */
public final class ToolErrorBuckets {

    private ToolErrorBuckets() {}

    /** {@code metric_baseline.bucket_kind}'s value, and what a finding's evidence reports. Never renamed. */
    public static final String KIND = "tool";

    /**
     * The bucket a tool's calls fall into: {@code ActionSymbol.of("tool", name)}.
     *
     * <p>The same alphabet {@code tool_duration} buckets on, so a tool-error finding and a tool-duration
     * finding name the same thing and a reader can hold both at once. That also means the normalization
     * is shared: {@code search_docs_3} and {@code search_docs_4} are one tool here exactly as they are
     * there, which keeps a bucket thick enough to have a rate worth watching.
     */
    public static String toolKey(@Nullable String toolName) {
        return ActionSymbol.of(KIND, toolName);
    }
}
