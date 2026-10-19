// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

/**
 * One synchronous chat completion on the org's own provider key. It neither prices nor books the call: the caller does
 * both, so a failed call books nothing.
 */
public interface ChatClient {

    /** @throws ai.tessary.open.errors.TessaryException with a {@link ai.tessary.open.errors.PlaygroundError} code */
    ChatResult complete(ChatTarget target, ChatRequest request);
}
