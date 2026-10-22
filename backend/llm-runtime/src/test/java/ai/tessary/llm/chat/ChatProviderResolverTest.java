// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.PlaygroundError;
import ai.tessary.open.errors.TessaryException;
import java.net.URI;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which endpoint, model and key a playground run calls with, against a real {@link SecretBox}. The bug class is a call
 * made with no key (a provider 401 the SPA would read as a sign-out), a CUSTOM run sent under the catalog's
 * representative model, or a run that reaches a private address through a saved base URL.
 */
@ExtendWith(MockitoExtension.class)
class ChatProviderResolverTest {

    private static final String ORG = "org_1";

    /** A public address literal: the URL guard accepts it without a DNS lookup. */
    private static final String PUBLIC_BASE = "https://203.0.113.10/v1/";

    @Mock
    private ProviderCredentialRepository credentials;

    private static SecretBox keyedBox() {
        TessaryProperties props = new TessaryProperties();
        props.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new SecretBox(props);
    }

    private static ProviderCredential credential(
            ModelProvider provider,
            @Nullable String sealedKey,
            @Nullable String baseUrl,
            @Nullable String customModelName) {
        return new ProviderCredential(
                "c1",
                ORG,
                null,
                provider,
                baseUrl,
                sealedKey,
                null,
                null,
                null,
                null,
                customModelName,
                ProviderCredential.AUTH_MODE_API_KEY,
                "t0",
                "t0");
    }

    static Stream<Arguments> unusableCredentials() {
        return Stream.of(
                Arguments.of("no credential for the provider", null),
                Arguments.of("saved without a key", credential(ModelProvider.OPENAI, null, PUBLIC_BASE, null)),
                Arguments.of("saved with a blank key", credential(ModelProvider.OPENAI, " ", PUBLIC_BASE, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableCredentials")
    void aMissingOrBlankKey_isMissingCredentials(String why, @Nullable ProviderCredential stored) {
        when(credentials.findByOrgAndProvider(ORG, ModelProvider.OPENAI)).thenReturn(Optional.ofNullable(stored));
        ChatProviderResolver resolver = new ChatProviderResolver(credentials, keyedBox());

        TessaryException e =
                assertThrows(TessaryException.class, () -> resolver.resolve(ORG, ModelProvider.OPENAI, "gpt-5.5"), why);

        assertSame(ModelConfigError.MISSING_CREDENTIALS, e.error(), why);
    }

    /** CUSTOM's catalog entry is only representative; the credential names the model the endpoint serves. */
    @Test
    void aCustomCredential_takesItsOwnModelName() {
        SecretBox box = keyedBox();
        ChatProviderResolver resolver = new ChatProviderResolver(credentials, box);
        when(credentials.findByOrgAndProvider(ORG, ModelProvider.CUSTOM))
                .thenReturn(
                        Optional.of(credential(ModelProvider.CUSTOM, box.seal("k1"), PUBLIC_BASE, "llama-3")),
                        Optional.of(credential(ModelProvider.CUSTOM, box.seal("k1"), PUBLIC_BASE, null)),
                        Optional.of(credential(ModelProvider.CUSTOM, box.seal("k1"), PUBLIC_BASE, " ")));

        assertEquals(
                "llama-3", resolver.resolve(ORG, ModelProvider.CUSTOM, "custom").modelId());
        String unnamed = PlaygroundError.PROVIDER_UNSUPPORTED.render(
                ModelProvider.CUSTOM, "the credential names no custom_model_name");
        assertEquals(
                unnamed,
                assertThrows(TessaryException.class, () -> resolver.resolve(ORG, ModelProvider.CUSTOM, "custom"))
                        .getMessage(),
                "no name");
        assertEquals(
                unnamed,
                assertThrows(TessaryException.class, () -> resolver.resolve(ORG, ModelProvider.CUSTOM, "custom"))
                        .getMessage(),
                "a blank name");
    }

    /** The guard runs here, not in the HTTP client, so a loopback base URL never receives the org's key. */
    @Test
    void aPrivateBaseUrl_isRejectedAtResolveTime() {
        SecretBox box = keyedBox();
        ChatProviderResolver resolver = new ChatProviderResolver(credentials, box);
        when(credentials.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(
                        Optional.of(credential(ModelProvider.OPENAI, box.seal("k1"), "http://127.0.0.1:1/v1", null)));

        TessaryException e =
                assertThrows(TessaryException.class, () -> resolver.resolve(ORG, ModelProvider.OPENAI, "gpt-5.5"));

        assertSame(IngestError.INVALID_BASE_URL, e.error());
    }

    /** Without the deployment key a sealed credential cannot be opened; that is a setup error, not a crash. */
    @Test
    void anUnconfiguredSecretKey_failsBeforeOpening() {
        SecretBox unconfigured = new SecretBox(new TessaryProperties());
        ChatProviderResolver resolver = new ChatProviderResolver(credentials, unconfigured);
        when(credentials.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(
                        Optional.of(credential(ModelProvider.OPENAI, keyedBox().seal("k1"), PUBLIC_BASE, null)));

        TessaryException e =
                assertThrows(TessaryException.class, () -> resolver.resolve(ORG, ModelProvider.OPENAI, "gpt-5.5"));

        assertSame(ModelConfigError.SECRET_KEY_NOT_CONFIGURED, e.error());
    }

    @Test
    void aKeyedCredential_resolvesToEndpointAndOpenedKey() {
        SecretBox box = keyedBox();
        ChatProviderResolver resolver = new ChatProviderResolver(credentials, box);
        when(credentials.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(credential(ModelProvider.OPENAI, box.seal("k1"), PUBLIC_BASE, "ignored")));

        assertEquals(
                new ChatTarget(
                        ModelProvider.OPENAI,
                        "gpt-5.5",
                        URI.create("https://203.0.113.10/v1/chat/completions"),
                        "k1",
                        ChatDialect.OPENAI_CHAT_COMPLETIONS),
                resolver.resolve(ORG, ModelProvider.OPENAI, "gpt-5.5"));
    }
}
