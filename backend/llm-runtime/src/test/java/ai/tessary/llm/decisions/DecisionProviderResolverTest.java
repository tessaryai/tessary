// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProjectModelSettingRepository;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llm.ProjectOrgResolver;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import ai.tessary.llm.catalog.ModelCatalogFetchService;
import ai.tessary.llmspi.ModelLane;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which provider, model, endpoint and key a decision lane calls with, resolved against a real
 * {@link ProjectModelSettings} and a real {@link SecretBox}. The bug class is a call made with no key
 * (the provider answers 401 on every turn) or one that throws instead of reporting "not configured",
 * which the frustration classifier reads as "pause", not "fail".
 */
@ExtendWith(MockitoExtension.class)
class DecisionProviderResolverTest {

    private static final String PID = "prj_1";
    private static final String ORG = "org_1";

    @Mock
    private ProjectOrgResolver orgs;

    @Mock
    private ProviderCredentialRepository credentials;

    @Mock
    private ProjectModelSettingRepository settingRows;

    @Mock
    private ModelCatalogFetchService catalog;

    private SecretBox box;
    private DecisionProviderResolver resolver;

    @BeforeEach
    void setUp() {
        TessaryProperties props = new TessaryProperties();
        props.setSecretKey(Base64.getEncoder().encodeToString(new byte[32]));
        box = new SecretBox(props);
        ProjectModelSettings settings = new ProjectModelSettings(settingRows, credentials, orgs, catalog);
        resolver = new DecisionProviderResolver(settings, credentials, box, orgs);
    }

    private static ProviderCredential typesafe(@Nullable String sealedKey, @Nullable String baseUrl) {
        return new ProviderCredential(
                "c1",
                ORG,
                null,
                ModelProvider.TYPESAFE,
                baseUrl,
                sealedKey,
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                "t0",
                "t0");
    }

    @Test
    void aProjectWithNoOrgResolvesNothing() {
        when(orgs.orgIdFor(PID)).thenReturn(null);

        assertEquals(Optional.empty(), resolver.resolve(PID, ModelLane.FRUSTRATION));
    }

    static Stream<Arguments> unusableCredentials() {
        return Stream.of(
                Arguments.of("no provider configured", List.of(), null),
                Arguments.of("the credential was deleted between reads", List.of(typesafe(null, null)), null),
                Arguments.of("saved without a key", List.of(typesafe(null, null)), typesafe(null, null)),
                Arguments.of("saved with a blank key", List.of(typesafe(" ", null)), typesafe(" ", null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableCredentials")
    void aLaneWithNoKeyToCallWithResolvesNothing(
            String why, List<ProviderCredential> roster, @Nullable ProviderCredential lookedUp) {
        when(orgs.orgIdFor(PID)).thenReturn(ORG);
        when(credentials.findByOrg(ORG)).thenReturn(roster);
        if (!roster.isEmpty()) {
            when(credentials.findByOrgAndProvider(ORG, ModelProvider.TYPESAFE))
                    .thenReturn(Optional.ofNullable(lookedUp));
        }

        assertEquals(Optional.empty(), resolver.resolve(PID, ModelLane.FRUSTRATION), why);
    }

    @Test
    void aKeyedCredentialResolvesToItsModelEndpointAndOpenedKey() {
        ProviderCredential cred = typesafe(box.seal("ts-key"), "https://gw.example.com/v1/");
        when(orgs.orgIdFor(PID)).thenReturn(ORG);
        when(credentials.findByOrg(ORG)).thenReturn(List.of(cred));
        when(credentials.findByOrgAndProvider(ORG, ModelProvider.TYPESAFE)).thenReturn(Optional.of(cred));

        assertEquals(
                Optional.of(new DecisionTarget(
                        ModelProvider.TYPESAFE,
                        "jev-latest",
                        URI.create("https://gw.example.com/v1/systemone"),
                        "ts-key")),
                resolver.resolve(PID, ModelLane.FRUSTRATION));
    }
}
