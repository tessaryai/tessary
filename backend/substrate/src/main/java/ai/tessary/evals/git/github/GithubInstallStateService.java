// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git.github;

import ai.tessary.evals.crypto.SecretBox;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.GitError;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the {@code state} carried through the GitHub App install redirect.
 * Because the callback lands outside the cookie session ({@link
 * ai.tessary.evals.auth.AuthFilter} bypasses it), the SecretBox-sealed (AES-GCM) state
 * IS the credential: it proves the install was initiated for this project and hasn't
 * expired. Tamper/forge fails the AEAD open; an old token fails the expiry check.
 */
@Service
public class GithubInstallStateService {

    private static final long TTL_SECONDS = 600;

    private final SecretBox secretBox;
    private final ObjectMapper mapper;

    public GithubInstallStateService(SecretBox secretBox, ObjectMapper mapper) {
        this.secretBox = secretBox;
        this.mapper = mapper;
    }

    public record StatePayload(String orgSlug, String projectSlug, String projectId, long exp) {}

    /**
     * The sealed blob carried to the multi-repo picker when "connect an existing installation"
     * resolves more than one candidate. {@code installationIds} are the installations the
     * authorizing user was just proven to administer (via {@code GET /user/installations}); the
     * select endpoint re-opens this to bound the user's pick to that verified set — so the IDOR
     * defense survives even though the picker runs on a later, separate request.
     */
    public record SelectionPayload(
            String orgSlug, String projectSlug, String projectId, List<Long> installationIds, long exp) {}

    public String mint(String orgSlug, String projectSlug, String projectId) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        try {
            return secretBox.seal(mapper.writeValueAsString(new StatePayload(orgSlug, projectSlug, projectId, exp)));
        } catch (Exception e) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID, e);
        }
    }

    public StatePayload verify(String state) {
        if (state == null || state.isBlank()) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID);
        }
        StatePayload p;
        try {
            p = mapper.readValue(secretBox.open(state), StatePayload.class);
        } catch (Exception e) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID, e);
        }
        if (p.exp() < Instant.now().getEpochSecond()) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID);
        }
        return p;
    }

    public String mintSelection(String orgSlug, String projectSlug, String projectId, List<Long> installationIds) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        try {
            return secretBox.seal(mapper.writeValueAsString(
                    new SelectionPayload(orgSlug, projectSlug, projectId, installationIds, exp)));
        } catch (Exception e) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID, e);
        }
    }

    public SelectionPayload verifySelection(String token) {
        if (token == null || token.isBlank()) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID);
        }
        SelectionPayload p;
        try {
            p = mapper.readValue(secretBox.open(token), SelectionPayload.class);
        } catch (Exception e) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID, e);
        }
        if (p.exp() < Instant.now().getEpochSecond()) {
            throw new EvalsException(GitError.INSTALL_STATE_INVALID);
        }
        return p;
    }
}
