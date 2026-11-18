// SPDX-License-Identifier: Apache-2.0
import { useId, type ReactNode } from "react";
import { AlertTriangle, XCircle } from "lucide-react";
import type { VersionSummary } from "../../api/types";
import { Badge, Card, Select } from "../../ui";

/** `production` is the label applications fetch by default, so it alone wears the accent. */
export function LabelBadge({ label, text }: { label: string; text: string }) {
  return <Badge tone={label === "production" ? "accent" : "neutral"}>{text}</Badge>;
}

const NOTE = {
  error: { Icon: XCircle, color: "text-error" },
  warning: { Icon: AlertTriangle, color: "text-warning" },
};

/** Grey text with a status icon: a status hue is never body text, and warning shares info's blue. */
export function Note({ tone, children }: { tone: "error" | "warning"; children: ReactNode }) {
  const { Icon, color } = NOTE[tone];
  return (
    <p className="flex items-start gap-1.5 text-small text-fg-secondary">
      <Icon size={13} strokeWidth={1.75} aria-hidden="true" className={`mt-0.5 shrink-0 ${color}`} />
      <span>{children}</span>
    </p>
  );
}

/** A titled card; `aside` is a short note on the title's line. */
export function Panel({ title, aside, children }: { title: ReactNode; aside?: ReactNode; children: ReactNode }) {
  return (
    <Card className="flex flex-col gap-3 border border-border p-5">
      <div className="flex items-baseline justify-between gap-4">
        <h3 className="text-h3 text-fg">{title}</h3>
        {aside && <div className="text-right text-small text-muted">{aside}</div>}
      </div>
      {children}
    </Card>
  );
}

/** "Version 3 · staging, latest": production first, latest last, as a select option names a version. */
export function versionOption(v: Pick<VersionSummary, "version" | "labels">): string {
  const labels = [...v.labels].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
  return labels.length ? `Version ${v.version} · ${labels.join(", ")}` : `Version ${v.version}`;
}
const rank = (label: string) => (label === "production" ? 0 : label === "latest" ? 2 : 1);

/** A select of the loaded versions, each named by its deployment labels. */
export function VersionSelect({
  label,
  value,
  versions,
  onChange,
}: {
  label: string;
  value: number;
  versions: VersionSummary[];
  onChange: (version: number) => void;
}) {
  const id = useId();
  const known = versions.some((v) => v.version === value);
  return (
    <span className="flex items-center gap-2">
      <label htmlFor={id} className="text-small text-muted">
        {label}
      </label>
      <span className="w-[220px]">
        <Select id={id} value={String(value)} onChange={(e) => onChange(Number(e.target.value))}>
          {!known && <option value={value}>Version {value}</option>}
          {versions.map((v) => (
            <option key={v.id} value={v.version}>
              {versionOption(v)}
            </option>
          ))}
        </Select>
      </span>
    </span>
  );
}

/** The name of a list of variables, then one chip per variable, or "None yet". */
export function VariableChips({ names }: { names: string[] }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-small text-muted">
      Variables
      {names.length === 0 ? (
        <span className="text-subtle">None yet</span>
      ) : (
        names.map((v) => (
          <code key={v} className="rounded-micro border border-border bg-raised px-1.5 font-mono text-code text-fg-secondary">
            {v}
          </code>
        ))
      )}
    </div>
  );
}
