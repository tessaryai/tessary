// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.llm.ModelProvider;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Where a playground call goes and over which wire. The bug class is a call sent to a path the provider does not serve
 * (a lost or doubled {@code /v1}, a stray slash), or a provider with no chat wire reaching the HTTP client at all.
 */
class ChatTargetTest {

    /** The chat base URL keeps its version segment; only trailing slashes go before the path is appended. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
            nullValues = "null",
            value = {
                "GEMINI, null, https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                "ANTHROPIC, null, https://api.anthropic.com/v1/messages",
                "OPENAI, ' https://gw.example.com/v1// ', https://gw.example.com/v1/chat/completions",
                "OPENROUTER, '  ', https://openrouter.ai/api/v1/chat/completions"
            })
    void endpointFor_stripsTrailingSlashesBeforeThePath(
            ModelProvider provider, @Nullable String override, String expected) {
        assertEquals(URI.create(expected), ChatTarget.endpointFor(provider, override));
    }

    /** CUSTOM has no default base URL, so a credential without one has nowhere to send the call. */
    @Test
    void endpointFor_refusesACustomCredentialWithNoBaseUrl() {
        TessaryException e =
                assertThrows(TessaryException.class, () -> ChatTarget.endpointFor(ModelProvider.CUSTOM, null));

        assertSame(PlaygroundError.PROVIDER_UNSUPPORTED, e.error());
        assertEquals(
                PlaygroundError.PROVIDER_UNSUPPORTED.render(ModelProvider.CUSTOM, "the credential names no base URL"),
                e.getMessage());
    }

    /** A saved base URL that is not a URI is the caller's input error, not a 500. */
    @Test
    void endpointFor_rejectsABaseUrlThatIsNotAUri() {
        TessaryException e = assertThrows(
                TessaryException.class,
                () -> ChatTarget.endpointFor(ModelProvider.OPENAI, "https://gw example.com/v1"));

        assertSame(IngestError.INVALID_BASE_URL, e.error());
    }

    @ParameterizedTest
    @EnumSource(
            value = ModelProvider.class,
            names = {"BEDROCK", "BEDROCK_MANTLE", "TYPESAFE", "PLATFORM"})
    void dialectFor_refusesProvidersWithNoChatWire(ModelProvider provider) {
        TessaryException e = assertThrows(TessaryException.class, () -> ChatTarget.dialectFor(provider));

        assertSame(PlaygroundError.PROVIDER_UNSUPPORTED, e.error());
    }

    /** Anthropic is the one provider on its own wire; every other chat provider speaks OpenAI chat completions. */
    @ParameterizedTest
    @EnumSource(
            value = ModelProvider.class,
            names = {"OPENAI", "OPENROUTER", "MOONSHOT", "GEMINI", "GLM", "GROK", "CUSTOM"})
    void dialectFor_putsEveryOtherChatProviderOnOpenAiChatCompletions(ModelProvider provider) {
        assertEquals(ChatDialect.OPENAI_CHAT_COMPLETIONS, ChatTarget.dialectFor(provider));
        assertEquals(ChatDialect.ANTHROPIC_MESSAGES, ChatTarget.dialectFor(ModelProvider.ANTHROPIC));
    }

    /** A CUSTOM credential pointed at OpenRouter is billed by OpenRouter, so its reported cost is the one to book. */
    @Test
    void billedByOpenRouter_isTrueForTheProviderOrTheHost() {
        assertEquals(
                true,
                target(ModelProvider.OPENROUTER, "https://gw.example.com/v1/chat/completions")
                        .billedByOpenRouter());
        assertEquals(
                true,
                target(ModelProvider.CUSTOM, "https://OpenRouter.ai/api/v1/chat/completions")
                        .billedByOpenRouter());
        assertEquals(
                false,
                target(ModelProvider.OPENAI, "https://api.openai.com/v1/chat/completions")
                        .billedByOpenRouter());
    }

    private static ChatTarget target(ModelProvider provider, String endpoint) {
        return new ChatTarget(provider, "m", URI.create(endpoint), "k1", ChatDialect.OPENAI_CHAT_COMPLETIONS);
    }
}
