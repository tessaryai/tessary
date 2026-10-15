// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the install redirect's {@code state}. The callback bypasses the cookie session, so the
 * SecretBox-sealed state is the credential: forgery fails the AEAD open, and an old token fails the expiry check.
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
     * Carried to the multi-repo picker. {@code installationIds} are those the user was just proven to administer; the
     * select endpoint bounds the pick to that set, so the IDOR defense survives the separate request.
     */
    public record SelectionPayload(
            String orgSlug, String projectSlug, String projectId, List<Long> installationIds, long exp) {}

    public String mint(String orgSlug, String projectSlug, String projectId) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        try {
            return secretBox.seal(mapper.writeValueAsString(new StatePayload(orgSlug, projectSlug, projectId, exp)));
        } catch (Exception e) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID, e);
        }
    }

    public StatePayload verify(String state) {
        if (state == null || state.isBlank()) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID);
        }
        StatePayload p;
        try {
            p = mapper.readValue(secretBox.open(state), StatePayload.class);
        } catch (Exception e) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID, e);
        }
        if (p.exp() < Instant.now().getEpochSecond()) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID);
        }
        return p;
    }

    public String mintSelection(String orgSlug, String projectSlug, String projectId, List<Long> installationIds) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        try {
            return secretBox.seal(mapper.writeValueAsString(
                    new SelectionPayload(orgSlug, projectSlug, projectId, installationIds, exp)));
        } catch (Exception e) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID, e);
        }
    }

    public SelectionPayload verifySelection(String token) {
        if (token == null || token.isBlank()) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID);
        }
        SelectionPayload p;
        try {
            p = mapper.readValue(secretBox.open(token), SelectionPayload.class);
        } catch (Exception e) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID, e);
        }
        if (p.exp() < Instant.now().getEpochSecond()) {
            throw new TessaryException(GitError.INSTALL_STATE_INVALID);
        }
        return p;
    }
}
