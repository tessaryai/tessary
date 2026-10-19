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
  onCompare,
}: {
  versions: VersionSummary[];
  selected: number;
  onSelect: (version: number) => void;
  onCompare: () => void;
}) {
  const single = versions.length < 2;
  return (
    <div className="flex flex-col gap-1 rounded-card border border-border bg-surface p-2">
      <div className="flex items-center justify-between px-1.5 py-1">
        <h2 className="text-small text-fg">Versions</h2>
        <Button size="sm" disabled={single} onClick={onCompare}>
          Compare
        </Button>
      </div>
      {single && <p className="px-1.5 text-small text-subtle">Add a second version to compare.</p>}
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
            <span className="text-label text-muted">
              {v.created_by_label} · {relativeTime(v.created_at)}
            </span>
          </button>
        ))}
      </nav>
    </div>
  );
}
