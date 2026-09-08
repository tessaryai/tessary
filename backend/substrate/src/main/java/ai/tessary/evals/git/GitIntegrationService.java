// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.git;

import ai.tessary.evals.crypto.SecretBox;
import ai.tessary.evals.git.GitIntegrationDtos.ConnectRequest;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.GitError;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.tenant.Ids;
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

    public GitIntegrationService(GitIntegrationRepository repo, SecretBox secretBox, ObjectMapper mapper) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.mapper = mapper;
    }

    public Optional<GitIntegrationRow> find(String projectId) {
        return repo.findByProject(projectId);
    }

    /** The integration for a project, or 404. Used by the observer pipeline. */
    public GitIntegrationRow require(String projectId) {
        return repo.findByProject(projectId)
                .orElseThrow(() -> new EvalsException(GitError.INTEGRATION_NOT_FOUND, projectId));
    }

    public GitIntegrationRow connect(String projectId, ConnectRequest req) {
        GitProvider provider;
        try {
            provider = GitProvider.fromWire(req.provider());
        } catch (IllegalArgumentException e) {
            throw new EvalsException(GitError.UNSUPPORTED_PROVIDER, e, req.provider());
        }
        if (repo.findByProject(projectId).isPresent()) {
            throw new EvalsException(GitError.DUPLICATE_INTEGRATION, projectId);
        }

        String credentialsEnc = sealCredentials(provider, req);
        String now = Instant.now().toString();
        String branch = (req.defaultBranch() == null || req.defaultBranch().isBlank()) ? "main" : req.defaultBranch();
        GitIntegrationRow row = new GitIntegrationRow(
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
        repo.insert(row);
        log.info(
                Markers.OPS,
                "git integration connected projectId={} provider={} repo={}/{}",
                projectId,
                provider.wire(),
                req.repoOwner(),
                req.repoName());
        return row;
    }

    public boolean delete(String projectId) {
        boolean deleted = repo.deleteByProject(projectId);
        if (deleted) log.info(Markers.OPS, "git integration removed projectId={}", projectId);
        return deleted;
    }

    private String sealCredentials(GitProvider provider, ConnectRequest req) {
        Map<String, Object> creds = new LinkedHashMap<>();
        if (req.installationId() != null) creds.put("installationId", req.installationId());
        if (req.token() != null && !req.token().isBlank()) creds.put("token", req.token());
        if (creds.isEmpty()) return null;
        if (!secretBox.isConfigured()) {
            throw new EvalsException(GitError.MISSING_APP_CONFIG, provider.wire());
        }
        try {
            return secretBox.seal(mapper.writeValueAsString(creds));
        } catch (Exception e) {
            throw new EvalsException(GitError.MISSING_APP_CONFIG, e, provider.wire());
        }
    }
}
