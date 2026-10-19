// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One non-streamed chat call. A null setting or an empty {@code stop} is not sent, so the provider's default applies. */
public record ChatRequest(
        List<ChatMessage> messages,
        @Nullable Double temperature,
        @Nullable Integer maxTokens,
        @Nullable Double topP,
        List<String> stop) {

    public ChatRequest {
        messages = List.copyOf(messages);
        stop = List.copyOf(stop);
    }
}
