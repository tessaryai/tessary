// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

/**
 * The kind of selectable scope an upstream observability platform exposes for a selective pull.
 * A user picks a scope of one of these kinds to bound what gets pulled into the
 * agent-native substrate; the {@link IngestionSource#enumerateScopes(ScopeKind)} capability lists
 * the concrete {@link Scope}s of a kind a given vendor offers.
 *
 * <ul>
 *   <li>{@link #PROJECT} — the vendor's top-level project/organization partition (every platform has
 *       one; it is the {@code projectId} already carried on {@link ImportFilter}).</li>
 *   <li>{@link #DATASET} — a named, curated set of traces/items (Langfuse datasets, Braintrust
 *       datasets, Phoenix datasets).</li>
 *   <li>{@link #SAVED_VIEW} — a saved filter/query a team has named in the upstream UI; resolving it
 *       to concrete traces is the vendor's job, surfaced through {@link Selection#savedView()}.</li>
 * </ul>
 *
 * <p>Open by intent: a vendor that exposes none of a kind returns an empty list, and a vendor with a
 * kind not modeled here can still pull via the time-range / id-list selection — scope enumeration is
 * a discovery convenience, never the only way to bound a pull.
 */
public enum ScopeKind {
    PROJECT,
    DATASET,
    SAVED_VIEW
}
