// SPDX-License-Identifier: Apache-2.0
/*
 * The trace interior's two shared controls: the view segment and the Verdicts
 * pill. Kept surface-local.
 */
import { useMemo } from "react";
import { cn } from "../../ui";
import { MediaResolverContext } from "../components/PayloadViewer";
import { useTenant } from "../../tenant/TenantContext";

export type TraceView = "conversation" | "tree" | "timeline";

/**
 * Teaches the payload viewers inside a trace how to reach an externalized image.
 *
 * Ingest rewrites inline base64 to `{type:"image_ref", data:<mediaId>}`, so the
 * bytes are no longer in the payload — they are behind
 * `GET /api/orgs/{org}/projects/{proj}/media/{id}`, which needs the tenant slugs
 * the viewer has no way to know. Without a resolver in context the viewer is not
 * wrong, just blind: it falls back to a "stored image" chip. That fallback was
 * what every trace showed, because this provider did not exist and the context
 * defaulted to null everywhere.
 *
 * It wraps the whole trace interior rather than the Conversation view alone —
 * Tree and Timeline hang the same payload panes off their span rows, and an
 * image should not appear or vanish depending on which tab is open.
 */
export function TraceMedia({ children }: { children: React.ReactNode }) {
  const { orgSlug, projectSlug } = useTenant();
  const resolve = useMemo(() => {
    const base = `/api/orgs/${encodeURIComponent(orgSlug)}/projects/${encodeURIComponent(projectSlug)}/media`;
    return (mediaId: string) => `${base}/${encodeURIComponent(mediaId)}`;
  }, [orgSlug, projectSlug]);

  return <MediaResolverContext.Provider value={resolve}>{children}</MediaResolverContext.Provider>;
}

const VIEWS: { id: TraceView; label: string }[] = [
  { id: "conversation", label: "Conversation" },
  { id: "tree", label: "Tree" },
  { id: "timeline", label: "Timeline" },
];

/** Renderings of execution only — judgment is the pill, never a view. */
export function ViewSegment({
  view,
  onChange,
  compact,
}: {
  view: TraceView;
  onChange: (v: TraceView) => void;
  compact?: boolean;
}) {
  return (
    <div
      role="tablist"
      aria-label="Trace view"
      className="inline-flex overflow-hidden rounded-control border border-border-strong">
      {VIEWS.map((v, i) => (
        <button
          key={v.id}
          type="button"
          role="tab"
          aria-selected={view === v.id}
          onClick={() => onChange(v.id)}
          className={cn(
            "text-small transition-colors",
            i > 0 && "border-l border-border-strong",
            view === v.id ? "bg-selected text-fg" : "bg-transparent text-muted hover:text-fg",
          )}
          style={{
            padding: compact ? "4px 11px" : "5px 12px",
            transitionDuration: "var(--duration-micro)" }}
        >
          {v.label}
        </button>
      ))}
    </div>
  );
}

/* ── Evidence banner (?case=&verdict=) ───────────────────────────────────── */

/**
 * Pinned evidence strip — both variants: `turn N` targeted (Conversation
 * scrolls to the judged turn) and `whole trace` (banner only). Border comes
 * from the caller (border-b in the rail, rounded card on the full page).
 */
