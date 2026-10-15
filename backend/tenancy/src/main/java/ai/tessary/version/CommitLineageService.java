// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Resolves any platform node to the {@link ProjectVersionRow} (causing commit) it belongs to: via a direct
 * {@code project_version_id} FK on {@code trace}, or derived for a session from its newest trace. A new lineage-bearing
 * table must carry that FK (with {@code ON DELETE SET NULL} and an index) and register a {@link NodeKind}.
 */
@Service
public class CommitLineageService {

    /**
     * These names are the path token of {@code /versions/lineage/{nodeKind}/{nodeId}}. Removed kinds fail
     * {@link #parse}, so the endpoint answers 400 rather than 500.
     */
    public enum NodeKind {
        SESSION,
        TURN,
        TRACE,
        SPAN;

        /** Case-insensitive parse; empty when the kind is unknown (callers map that to a 400). */
        public static Optional<NodeKind> parse(String raw) {
            if (raw == null || raw.isBlank()) return Optional.empty();
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    private final CommitLineageRepository lineage;
    private final ProjectVersionRepository versions;

    public CommitLineageService(CommitLineageRepository lineage, ProjectVersionRepository versions) {
        this.lineage = lineage;
        this.versions = versions;
    }

    /** Empty when the node does not exist in this project or carries no lineage. */
    public Optional<ProjectVersionRow> resolve(String projectId, NodeKind kind, String nodeId) {
        return switch (kind) {
            case SESSION -> byId(projectId, lineage.sessionVersionId(projectId, nodeId));
            case TURN -> byId(projectId, lineage.turnVersionId(projectId, nodeId));
            case TRACE -> byId(projectId, lineage.traceVersionId(projectId, nodeId));
            // A span is identified by (project_id, trace_id, id), so a bare id cannot name one.
            case SPAN -> Optional.empty();
        };
    }

    private Optional<ProjectVersionRow> byId(String projectId, Optional<String> versionId) {
        return versionId.flatMap(id -> versions.findById(projectId, id));
    }
}
