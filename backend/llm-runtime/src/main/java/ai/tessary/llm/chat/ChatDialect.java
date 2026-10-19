// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

/** The two chat wires the playground speaks; {@link ChatTarget#dialectFor} maps each provider to one. */
public enum ChatDialect {
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES
}
