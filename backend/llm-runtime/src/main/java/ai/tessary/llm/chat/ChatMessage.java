// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

/** One turn of a chat request: {@code system}, {@code user} or {@code assistant}, and its text. */
public record ChatMessage(String role, String content) {}
