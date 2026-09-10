// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git;

import ai.tessary.crypto.SecretBox;
import ai.tessary.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.git.GitProviderClient.RepoAccess;
import ai.tessary.open.errors.GitError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GitIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(GitIntegrationService.class);

    private final GitIntegrationRepository repo;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final GitProviderFactory providers;

    public GitIntegrationService(
            GitIntegrationRepository repo, SecretBox secretBox, ObjectMapper mapper, GitProviderFactory providers) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.providers = providers;
    }

    public Optional<GitIntegrationRow> find(String projectId) {
        return repo.findByProject(projectId);
    }

    /** The integration for a project, or 404. Used by the observer pipeline. */
    public GitIntegrationRow require(String projectId) {
        return repo.findByProject(projectId)
                .orElseThrow(() -> new TessaryException(GitError.INTEGRATION_NOT_FOUND, projectId));
    }

    /**
     * Bind a repo whose reachability is already established by the flow that got here: the GitHub
     * App install and reuse callbacks, which enumerated the repo off an installation the authorizing
     * user was proved to administer. Stores without asking the provider anything.
     */
    public GitIntegrationRow connect(String projectId, ConnectRequest req) {
        return store(candidate(projectId, req));
    }

    /**
     * Bind a repo the caller only claims to be able to read, confirming it against the provider
     * first. This is the path behind {@code POST /git/connect}, where the credential is typed in
     * rather than handed to us by an install we watched happen.
     *
     * <p>Nothing is written unless the read succeeds. Without this, a typo'd or under-scoped token
     * binds cleanly and RCA silently drops to trace-only evidence at the next run, with nothing on
     * screen connecting the thin answer to the credential that caused it.
     *
     * <p>The provider also answers with the repo's real default branch, so an unspecified branch
     * lands on what the repo actually uses instead of a hardcoded {@code main}.
     */
    public GitIntegrationRow connectVerified(String projectId, ConnectRequest req) {
        GitIntegrationRow candidate = candidate(projectId, req);
        RepoAccess access = providers.client(candidate.providerEnum()).verifyAccess(candidate);
        boolean branchGiven =
                req.defaultBranch() != null && !req.defaultBranch().isBlank();
        return store(branchGiven ? candidate : withDefaultBranch(candidate, access.defaultBranch()));
    }

    /** The row a connect would write, sealed and validated, but not yet persisted. */
    private GitIntegrationRow candidate(String projectId, ConnectRequest req) {
        GitProvider provider;
        try {
            provider = GitProvider.fromWire(req.provider());
        } catch (IllegalArgumentException e) {
            throw new TessaryException(GitError.UNSUPPORTED_PROVIDER, e, req.provider());
        }
        if (repo.findByProject(projectId).isPresent()) {
            throw new TessaryException(GitError.DUPLICATE_INTEGRATION, projectId);
        }

        String credentialsEnc = sealCredentials(req);
        String now = Instant.now().toString();
        String branch = (req.defaultBranch() == null || req.defaultBranch().isBlank()) ? "main" : req.defaultBranch();
        return new GitIntegrationRow(
                Ids.ulid(),
                projectId,
                provider.wire(),
                req.host(),
                req.repoOwner(),
                req.repoName(),
                branch,
                credentialsEnc,
                null,
                now,
                now);
    }

    private static GitIntegrationRow withDefaultBranch(GitIntegrationRow r, String branch) {
        return new GitIntegrationRow(
                r.id(),
                r.projectId(),
                r.provider(),
                r.host(),
                r.repoOwner(),
                r.repoName(),
                branch,
                r.credentialsEnc(),
                r.observerCursorSha(),
                r.createdAt(),
                r.updatedAt());
    }

    private GitIntegrationRow store(GitIntegrationRow row) {
        repo.insert(row);
        log.info(
                Markers.OPS,
                "git integration connected projectId={} provider={} repo={}/{}",
                row.projectId(),
                row.provider(),
                row.repoOwner(),
                row.repoName());
        return row;
    }

    public boolean delete(String projectId) {
        boolean deleted = repo.deleteByProject(projectId);
        if (deleted) log.info(Markers.OPS, "git integration removed projectId={}", projectId);
        return deleted;
    }

    private String sealCredentials(ConnectRequest req) {
        Map<String, Object> creds = new LinkedHashMap<>();
        if (req.installationId() != null) creds.put("installationId", req.installationId());
        if (req.token() != null && !req.token().isBlank()) creds.put("token", req.token());
        if (creds.isEmpty()) return null;
        if (!secretBox.isConfigured()) {
            throw new TessaryException(GitError.SECRET_KEY_MISSING);
        }
        try {
            return secretBox.seal(mapper.writeValueAsString(creds));
        } catch (Exception e) {
            throw new TessaryException(GitError.SECRET_KEY_MISSING, e);
        }
    }
}
