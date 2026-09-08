// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.version;

import java.util.List;

/** HTTP-shaped DTOs for /api/.../versions (the benchmark-by-version timeline). */
public final class ProjectVersionDtos {

    private ProjectVersionDtos() {}

    public record ProjectVersionView(
            String commitSha,
            String parentSha,
            String materializedReason,
            String gradersStatus,
            String datasetsStatus,
            String benchmarkStatus,
            String summary,
            String createdAt,
            String updatedAt) {
        static ProjectVersionView from(ProjectVersionRow r) {
            return new ProjectVersionView(
                    r.commitSha(),
                    r.parentSha(),
                    r.materializedReason(),
                    r.gradersStatus(),
                    r.datasetsStatus(),
                    r.benchmarkStatus(),
                    r.summary(),
                    r.createdAt(),
                    r.updatedAt());
        }
    }

    public record TimelineView(List<ProjectVersionView> versions) {}
}
