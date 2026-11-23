// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import org.jspecify.annotations.Nullable;

/**
 * One answered chat call. {@code respondedModel} is the id the provider echoed, else the requested one;
 * {@code latencyMs} is wall time including retries.
 */
public record ChatResult(
        String content, @Nullable String finishReason, String respondedModel, ChatUsage usage, long latencyMs) {}
