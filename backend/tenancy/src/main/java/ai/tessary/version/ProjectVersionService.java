// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import ai.tessary.version.ProjectVersionRepository.Aspect;
import org.springframework.stereotype.Service;

/**
 * Facade over project versions (commit SHAs). Versions are materialized lazily
 * by {@code reasonPipelineSync} at import, which also records that the graders
 * are in sync for that commit.
 */
@Service
public class ProjectVersionService {

    private final ProjectVersionRepository repo;

    public ProjectVersionService(ProjectVersionRepository repo) {
        this.repo = repo;
    }

    public void reasonPipelineSync(String projectId, String commitSha) {
        repo.findOrMaterialize(projectId, commitSha, ProjectVersionRow.REASON_PIPELINE_SYNC);
        repo.setAspectStatus(projectId, commitSha, Aspect.GRADERS, ProjectVersionRow.STATUS_SYNCED);
    }
}
