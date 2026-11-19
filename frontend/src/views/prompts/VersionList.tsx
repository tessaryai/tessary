// SPDX-License-Identifier: Apache-2.0
import type { VersionSummary } from "../../api/types";
import { relativeTime } from "../../lib/relativeTime";
import { Button, cn } from "../../ui";
import { LabelBadge } from "./promptBits";

/** A plain column, not the Rail primitive, which is a portalled overlay. */
export function VersionList({
  versions,
  selected,
  onSelect,
  hasOlder,
  onShowOlder,
  loadingOlder,
}: {
  versions: VersionSummary[];
  selected: number;
  onSelect: (version: number) => void;
  /** Versions older than the last one listed exist and are not read yet. */
  hasOlder: boolean;
  onShowOlder: () => void;
  loadingOlder: boolean;
}) {
  const n = versions.length;
  return (
    <div className="flex flex-col gap-1 rounded-card border border-border bg-surface p-2">
      <div className="flex items-center justify-between px-1.5 py-1.5">
        <h2 className="text-body font-medium text-fg">
          {hasOlder ? `Newest ${n} versions` : `${n} ${n === 1 ? "version" : "versions"}`}
        </h2>
        <span className="text-small text-muted">Newest first</span>
      </div>
      <nav aria-label="Versions" className="flex flex-col gap-0.5">
        {versions.map((v) => (
          <button
            key={v.id}
            type="button"
            aria-label={`Version ${v.version}`}
            aria-pressed={v.version === selected}
            onClick={() => onSelect(v.version)}
            className={cn(
              "flex flex-col gap-1 rounded-control p-2.5 text-left transition-colors",
              v.version === selected ? "bg-selected text-fg" : "hover:bg-hover",
            )}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            <span className="flex flex-wrap items-center gap-1.5">
              <span className="font-mono text-small text-fg">v{v.version}</span>
              {v.labels.map((l) => (
                <LabelBadge key={l} label={l} text={l} />
              ))}
            </span>
            <span className={cn("truncate text-small", v.commit_message ? "text-fg-secondary" : "text-subtle")}>
              {v.commit_message ?? "No commit message"}
            </span>
            <span className="text-small text-muted">
              {v.created_by_label} · {relativeTime(v.created_at)}
            </span>
          </button>
        ))}
      </nav>
      {hasOlder && (
        <Button variant="ghost" size="sm" loading={loadingOlder} onClick={onShowOlder}>
          Show older versions
        </Button>
      )}
    </div>
  );
}
