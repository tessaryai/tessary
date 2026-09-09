// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.errors.VersionError;
import ai.tessary.version.ProjectVersionDtos.ProjectVersionView;
import ai.tessary.version.ProjectVersionRepository.Aspect;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Facade over project versions (commit SHAs). Versions are materialized lazily
 * by callers that attach to a SHA — {@code reasonPipelineSync} at import,
 * {@code reasonBenchmark} when a run executes, {@code reasonObserverFinding}
 * when the observer triages a commit. Aspect statuses record, per version,
 * whether graders / datasets / the benchmark are in sync for that commit.
 */
@Service
public class ProjectVersionService {

    private final ProjectVersionRepository repo;

    public ProjectVersionService(ProjectVersionRepository repo) {
        this.repo = repo;
    }

    public ProjectVersionRow reasonPipelineSync(String projectId, String commitSha) {
        ProjectVersionRow row = repo.findOrMaterialize(projectId, commitSha, ProjectVersionRow.REASON_PIPELINE_SYNC);
        repo.setAspectStatus(projectId, commitSha, Aspect.GRADERS, ProjectVersionRow.STATUS_SYNCED);
        return row;
    }

    public ProjectVersionRow reasonBenchmark(String projectId, String commitSha) {
        return repo.findOrMaterialize(projectId, commitSha, ProjectVersionRow.REASON_BENCHMARK);
    }

    public ProjectVersionRow reasonObserverFinding(String projectId, String commitSha) {
        return repo.findOrMaterialize(projectId, commitSha, ProjectVersionRow.REASON_OBSERVER_FINDING);
    }

    public void markGraders(String projectId, String commitSha, String status) {
        repo.setAspectStatus(projectId, commitSha, Aspect.GRADERS, status);
    }

    public void markDatasets(String projectId, String commitSha, String status) {
        repo.setAspectStatus(projectId, commitSha, Aspect.DATASETS, status);
    }

    public void markBenchmark(String projectId, String commitSha, String status) {
        repo.setAspectStatus(projectId, commitSha, Aspect.BENCHMARK, status);
    }

    public ProjectVersionView get(String projectId, String commitSha) {
        return repo.findByCommit(projectId, commitSha)
                .map(ProjectVersionView::from)
                .orElseThrow(() -> new TessaryException(VersionError.NOT_FOUND, commitSha));
    }

    public List<ProjectVersionView> timeline(String projectId) {
        return repo.listTimeline(projectId).stream()
                .map(ProjectVersionView::from)
                .toList();
    }
}
