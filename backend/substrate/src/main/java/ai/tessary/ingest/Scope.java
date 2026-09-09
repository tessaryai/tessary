// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import org.jspecify.annotations.Nullable;

/**
 * One selectable scope discovered on an upstream source: a vendor project, dataset, or
 * saved view a user can choose to bound a selective pull. Returned by
 * {@link IngestionSource#enumerateScopes(ScopeKind)}.
 *
 * @param kind which selectable partition this is
 * @param id the vendor-native identifier of the scope — the value that flows back as the
 *     {@link ImportFilter#projectId()} (for {@link ScopeKind#PROJECT}) or the
 *     {@link Selection#savedView()} (for {@link ScopeKind#DATASET}/{@link ScopeKind#SAVED_VIEW}).
 * @param name a human label for the scope (the vendor's display name); falls back to {@code id} when
 *     the vendor exposes no separate label.
 * @param itemCount an optional count of items in the scope, when the vendor reports one cheaply —
 *     {@code null} when unknown (never fetched eagerly just to populate this).
 */
public record Scope(
        ScopeKind kind, String id, String name, @Nullable Long itemCount) {

    public Scope(ScopeKind kind, String id, String name) {
        this(kind, id, name, null);
    }
}
