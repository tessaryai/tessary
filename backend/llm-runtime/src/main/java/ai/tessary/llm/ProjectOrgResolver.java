// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Cached {@code projectId → orgId}, since credentials are keyed by org and every judge call would otherwise query for
 * it. No eviction: a project cannot change orgs, and a deleted project's entry is never looked up again. Shared by
 * {@link AgenticCredentialResolver} and {@link ProjectModelSettings} so there is one cache.
 */
@Component
public class ProjectOrgResolver {

    private final ProjectRepository projects;

    private final ConcurrentMap<String, String> orgIdByProject = new ConcurrentHashMap<>();

    public ProjectOrgResolver(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * Null when the project cannot be found, so a stale project id degrades to "no credential" rather than throwing.
     */
    public @Nullable String orgIdFor(String projectId) {
        return orgIdByProject.computeIfAbsent(
                projectId, pid -> projects.findById(pid).map(Project::orgId).orElse(null));
    }
}
