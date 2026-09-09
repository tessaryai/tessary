// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code projectId → orgId}, cached (#939 D1 — credentials moved to {@code (org_id, provider)}, so
 * every credential-dependent resolve needs the project's org). A project's org membership changes
 * essentially never, so a Postgres round trip on every judge/agentic call would put a query in
 * front of grading for no benefit. There is no eviction because a project cannot change orgs; a
 * deleted project's stale entry is harmless — nothing looks it up again.
 *
 * <p>Extracted from {@link ChatModelFactory} (#939 TASK 3, corrective run 2026-09-04) once
 * {@link AgenticCredentialResolver} needed the identical lookup and had reimplemented it inline and
 * uncached — two independent copies of the same cache would drift or double the query load with no
 * behavioral benefit, so both now share one instance via Spring.
 */
@Component
public class ProjectOrgResolver {

    private final ProjectRepository projects;

    /** See the class javadoc for why this is a cache with no eviction. */
    private final ConcurrentMap<String, String> orgIdByProject = new ConcurrentHashMap<>();

    public ProjectOrgResolver(ProjectRepository projects) {
        this.projects = projects;
    }

    /**
     * {@code projectId → orgId}, via the cache above. Null when the project itself cannot be found —
     * a caller with a stale/deleted project id degrades to "no credential" (via the null-safe
     * {@code orgId == null ? null : repo.findByOrgAndProvider(...)} callers use) rather than throwing
     * here, since a missing project is a different failure than a missing credential.
     */
    public @Nullable String orgIdFor(String projectId) {
        return orgIdByProject.computeIfAbsent(
                projectId, pid -> projects.findById(pid).map(Project::orgId).orElse(null));
    }
}
