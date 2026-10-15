// SPDX-License-Identifier: Apache-2.0
import { useMemo } from "react";
import { cn } from "../../ui";
import { MediaResolverContext } from "../components/PayloadViewer";
import { useTenant } from "../../tenant/TenantContext";

export type TraceView = "conversation" | "tree" | "timeline" | "json";

/**
 * Ingest rewrites inline base64 to an image_ref behind a tenant-scoped endpoint the viewer cannot build.
 * Wraps the whole interior so an image does not depend on which tab is open.
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
  { id: "json", label: "JSON" },
];

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
