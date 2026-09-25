// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.config.TessaryProperties;
import ai.tessary.crypto.SecretBox;
import ai.tessary.git.github.GithubInstallStateService.SelectionPayload;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GithubInstallStateServiceTest {

    private static SecretBox secretBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    private final GithubInstallStateService svc =
            new GithubInstallStateService(secretBox(), new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void selection_roundTrips() {
        String token = svc.mintSelection("acme", "web", "p1", List.of(10L, 20L, 30L));
        SelectionPayload p = svc.verifySelection(token);
        assertEquals("acme", p.orgSlug());
        assertEquals("web", p.projectSlug());
        assertEquals("p1", p.projectId());
        assertEquals(List.of(10L, 20L, 30L), p.installationIds());
    }

    @Test
    void selection_rejectsGarbageAndBlank() {
        assertThrows(TessaryException.class, () -> svc.verifySelection("not-a-real-token"));
        assertThrows(TessaryException.class, () -> svc.verifySelection(""));
        assertThrows(TessaryException.class, () -> svc.verifySelection(null));
    }

    @Test
    void selection_rejectsTokenSealedByADifferentKey() {
        // A selection token minted under one server key must not open under another (forgery / key swap).
        GithubInstallStateService other =
                new GithubInstallStateService(otherKeyBox(), new com.fasterxml.jackson.databind.ObjectMapper());
        String token = svc.mintSelection("acme", "web", "p1", List.of(10L));
        assertThrows(TessaryException.class, () -> other.verifySelection(token));
    }

    @Test
    void plainState_stillRoundTrips() {
        // Regression: adding the selection variant must not disturb the original install state.
        String s = svc.mint("acme", "web", "p1");
        assertEquals("p1", svc.verify(s).projectId());
    }

    private static SecretBox otherKeyBox() {
        TessaryProperties p = new TessaryProperties();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (0xFF - i);
        p.setSecretKey(Base64.getEncoder().encodeToString(key));
        return new SecretBox(p);
    }

    /**
     * Both tokens carry a ten-minute expiry, and one past it must not open: a state lifted from an old
     * install link would otherwise bind whatever GitHub returns to the project it names, for ever.
     */
    @Test
    void expiredTokens_areRejectedEvenThoughTheySealCorrectly() {
        SecretBox box = secretBox();
        String state = box.seal("{\"orgSlug\":\"acme\",\"projectSlug\":\"web\",\"projectId\":\"p1\",\"exp\":1}");
        String selection = box.seal("{\"orgSlug\":\"acme\",\"projectSlug\":\"web\",\"projectId\":\"p1\","
                + "\"installationIds\":[10],\"exp\":1}");

        assertEquals(
                GitError.INSTALL_STATE_INVALID,
                assertThrows(TessaryException.class, () -> svc.verify(state)).error());
        assertEquals(
                GitError.INSTALL_STATE_INVALID,
                assertThrows(TessaryException.class, () -> svc.verifySelection(selection))
                        .error());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "not-a-real-state"})
    void plainState_rejectsBlankAndGarbage(String state) {
        assertEquals(
                GitError.INSTALL_STATE_INVALID,
                assertThrows(TessaryException.class, () -> svc.verify(state)).error());
    }

    /** With no server key nothing can be sealed, and that is a typed state failure rather than a raw 500. */
    @Test
    void minting_withoutASecretKeyIsInstallStateInvalid() {
        GithubInstallStateService unkeyed = new GithubInstallStateService(
                new SecretBox(new TessaryProperties()), new com.fasterxml.jackson.databind.ObjectMapper());

        assertEquals(
                GitError.INSTALL_STATE_INVALID,
                assertThrows(TessaryException.class, () -> unkeyed.mint("acme", "web", "p1"))
                        .error());
        assertEquals(
                GitError.INSTALL_STATE_INVALID,
                assertThrows(TessaryException.class, () -> unkeyed.mintSelection("acme", "web", "p1", List.of(10L)))
                        .error());
    }
}
