// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.version;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The commit-SHA lineage spine: resolve <em>any</em> platform node back to the
 * {@link ProjectVersionRow} (exact causing commit) it belongs to — given a node, find the commit.
 *
 * <p><b>Provenance shapes.</b> Lineage is stored in two shapes, both bridged here:
 * <ul>
 *   <li><b>Direct FK</b> — {@code project_version_id} on {@code trace} and {@code span},
 *       denormalized at ingest. The canonical shape; resolved via
 *       {@link ProjectVersionRepository#findById}.</li>
 *   <li><b>Derived</b> — a {@code session} carries no stamp of its own and resolves to the newest
 *       deploy any of its traces ran under.</li>
 * </ul>
 *
 * <p>A third shape, RAW SHA, existed until Track A: {@code observer_alert.project_version_sha}
 * stored the SHA string rather than the FK and resolved through
 * {@link ProjectVersionRepository#findByCommit}. It went with the observer, and a new column must
 * NOT bring it back — {@link ProjectVersionRepository#findByCommit} survives for the write-side
 * {@link #versionIdForSha} helper, which is the legitimate use of a raw SHA.
 *
 * <p><b>Forward contract for new node types.</b> A new lineage-bearing table MUST carry the
 * direct-FK shape from
 * day one — {@code project_version_id TEXT REFERENCES project_version(id) ON DELETE SET NULL} plus
 * an index — and register a {@link NodeKind} + resolution case here. That keeps "any detection
 * resolves to its causing commit" true without per-consumer retrofits.
 *
 * <p>Resolution is an on-demand read (single-row indexed lookups), never part of a hot loop —
 * write-side stamping resolves the version id once at write time.
 */
@Service
public class CommitLineageService {

    /**
     * The node types the lineage spine resolves today. {@code classifier} joins this enum when its
     * table carries the FK (see the forward contract above).
     *
     * <p>These names are the PATH TOKEN of {@code /versions/lineage/{nodeKind}/{nodeId}}, so the enum is
     * the public spelling of a node kind and not merely an internal label. {@link #SPAN} was
     * {@code OBSERVATION} until 0094 finished the v1 vocabulary rename: {@link #TURN} is kept beside
     * {@link #TRACE} because a turn IS a trace and callers minted links under both words, whereas nothing
     * ever linked to {@code /lineage/observation/…} — the frontend has no caller and the plugin does not
     * use this endpoint at all.
     *
     * <p>{@code VERDICT}, {@code OBSERVER_ALERT}, {@code DIFF_CLASSIFICATION} and {@code RISK_STAT}
     * were removed by Track A with the tables behind them. The path variable is a string, so those
     * spellings now fail {@link #parse} and the endpoint answers 400 rather than 500 — which is the
     * correct answer for a node kind that no longer exists.
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

    /**
     * Resolve a node to the project version (commit SHA) that caused it. Empty when the node does
     * not exist in this project or carries no lineage (e.g. a run created before the pipeline was
     * bound to a commit).
     */
    public Optional<ProjectVersionRow> resolve(String projectId, NodeKind kind, String nodeId) {
        return resolve(projectId, kind, null, nodeId);
    }

    /**
     * As {@link #resolve(String, NodeKind, String)}, with the trace a {@link NodeKind#SPAN} node
     * belongs to.
     *
     * <p>A span is identified by {@code (project_id, trace_id, id)}, so a SPAN node genuinely cannot be
     * resolved from one id — the three-argument overload passes null and correctly yields empty for that
     * kind rather than resolving against whichever trace reused the span id. Every other kind ignores
     * {@code traceId}.
     */
    public Optional<ProjectVersionRow> resolve(
            String projectId, NodeKind kind, @Nullable String traceId, String nodeId) {
        return switch (kind) {
            case SESSION -> byId(projectId, lineage.sessionVersionId(projectId, nodeId));
            case TURN -> byId(projectId, lineage.turnVersionId(projectId, nodeId));
            case TRACE -> byId(projectId, lineage.traceVersionId(projectId, nodeId));
            case SPAN ->
                traceId == null ? Optional.empty() : byId(projectId, lineage.spanVersionId(projectId, traceId, nodeId));
        };
    }

    private Optional<ProjectVersionRow> byId(String projectId, Optional<String> versionId) {
        return versionId.flatMap(id -> versions.findById(projectId, id));
    }

    /** Convenience for write paths: the version id a raw SHA maps to, if materialized. */
    public @Nullable String versionIdForSha(String projectId, @Nullable String commitSha) {
        if (commitSha == null || commitSha.isBlank()) return null;
        return versions.findByCommit(projectId, commitSha)
                .map(ProjectVersionRow::id)
                .orElse(null);
    }
}
