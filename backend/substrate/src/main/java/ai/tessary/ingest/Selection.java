// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What an on-demand selective pull should fetch from an upstream source: the canonical
 * {@link ImportFilter} (vendor project + time range + name/model/tag/environment) <em>plus</em> the
 * two selection axes the task adds on top — an explicit <b>trace/session id list</b> and a
 * <b>saved-view / dataset</b> handle.
 *
 * <p><b>Why wrap, not fork.</b> {@link ImportFilter} is the established selection seam every adapter
 * already honors in {@link IngestionSource#fetch(ImportFilter)}; the extra axes here are
 * pull-specific and absent from every other filter call-site, so wrapping keeps the adapter contract
 * unchanged (an adapter still pulls a window via {@code fetch(filter())}) while the pull runner
 * applies the id-list narrowing on top of the streamed window. A vendor connector that can resolve a
 * saved view or id list <em>server-side</em> may read {@link #savedView()} / {@link #traceIds()}
 * itself; the stub and the default path narrow client-side so the seam works for every adapter.
 *
 * @param filter the canonical window/attribute filter (never null; use {@link ImportFilter#empty()})
 * @param traceIds vendor-native trace ids to restrict the pull to; null/empty imposes no constraint
 * @param sessionIds vendor-native session/thread ids to restrict the pull to; null/empty imposes none
 * @param savedView a vendor saved-view or dataset handle ({@link Scope#id()} of a
 *     {@link ScopeKind#SAVED_VIEW} / {@link ScopeKind#DATASET}); null imposes no constraint
 */
public record Selection(
        ImportFilter filter,
        @Nullable List<String> traceIds,
        @Nullable List<String> sessionIds,
        @Nullable String savedView) {

    public Selection {
        traceIds = traceIds == null ? null : List.copyOf(traceIds);
        sessionIds = sessionIds == null ? null : List.copyOf(sessionIds);
    }

    /** A selection of a whole window with no id-list or saved-view narrowing. */
    public static Selection of(ImportFilter filter) {
        return new Selection(filter, null, null, null);
    }

    /** Whether any id-list constraint is present (so the pull narrows the streamed window client-side). */
    public boolean hasIdConstraint() {
        return (traceIds != null && !traceIds.isEmpty()) || (sessionIds != null && !sessionIds.isEmpty());
    }

    /**
     * Whether a {@link RawEntry} satisfies this selection's id-list constraint. An entry passes when
     * either its trace id is in {@link #traceIds()} or its {@code session.id} is in
     * {@link #sessionIds()} (or when no id constraint is set) — the same canonical session key the
     * substrate resolves on, so a session-id selection lands exactly the sessions the user picked.
     */
    public boolean accepts(RawEntry entry) {
        if (!hasIdConstraint()) return true;
        String traceId = entry.traceId();
        if (traceIds != null && traceId != null && traceIds.contains(traceId)) {
            return true;
        }
        var metadata = entry.metadata();
        if (sessionIds != null && metadata != null) {
            Object v = metadata.get(GenAiAttributes.SESSION_ID);
            return v instanceof String s && sessionIds.contains(s);
        }
        return false;
    }
}
