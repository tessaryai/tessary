// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Tag } from "lucide-react";
import type { ApiError, LabelRef } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { Badge, Button, Checkbox, cn, Field, Input, Modal, useToast } from "../../ui";
import { labelsWithoutLatest } from "./promptFolders";
import { invalidatePromptReads } from "./promptReads";
import { LabelBadge, Note } from "./promptBits";

const LABEL = /^[a-z0-9_.-]{1,36}$/;
const LABEL_RULE = "Use 1 to 36 lowercase letters, digits, _ . or -.";

/** production and staging always first, then every other label alphabetical, each once. */
export function orderLabels(labels: string[]): string[] {
  const rest = [...new Set(labels)].filter((l) => l !== "production" && l !== "staging").sort();
  return ["production", "staging", ...rest];
}

/** The `latest` row: never set or sent, only shown, ticked when the version is (or will be) the newest. */
export type LatestRow = { checked: boolean; hint?: string };

const ROW = "flex items-center gap-3 px-3 py-2";

/**
 * One checkbox per label, then a box to add one; reused by the labels dialog and both version forms. `boxed` draws
 * the rows in a raised panel, for a dialog.
 */
export function LabelChecklist({
  value,
  options,
  onChange,
  rowHint,
  latest,
  boxed = false,
  placeholder = "eu-rollout",
}: {
  value: string[];
  options: string[];
  onChange: (next: string[]) => void;
  rowHint?: (label: string) => string | null;
  latest?: LatestRow;
  boxed?: boolean;
  placeholder?: string;
}) {
  const [added, setAdded] = useState<string[]>([]);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const rows = orderLabels([...options, ...added, ...value]);

  const add = () => {
    const label = draft.trim();
    if (label === "latest") return setError("latest is derived from the highest version and cannot be set.");
    if (!LABEL.test(label)) return setError(LABEL_RULE);
    if (rows.includes(label)) return setError("This label is already in the list.");
    setAdded([...added, label]);
    onChange(orderLabels([...rows, label]).filter((r) => r === label || value.includes(r)));
    setDraft("");
    setError(null);
  };

  return (
    <div className="flex flex-col gap-4">
      <ul className={cn("flex flex-col", boxed && "rounded-card border border-border bg-raised py-1")}>
        {rows.map((label) => {
          const hint = rowHint?.(label);
          return (
            <li key={label} className={ROW}>
              <Checkbox
                label={label}
                checked={value.includes(label)}
                onChange={(on) => onChange(rows.filter((r) => (r === label ? on : value.includes(r))))}
              />
              <LabelBadge label={label} text={label} />
              {hint && <span className="ml-auto text-right text-small text-muted">{hint}</span>}
            </li>
          );
        })}
        {latest && (
          <li className={ROW}>
            <Checkbox label="latest" checked={latest.checked} disabled onChange={() => {}} />
            <Badge>latest</Badge>
            <span className="ml-auto text-right text-small text-muted">{latest.hint ?? "The newest version"}</span>
          </li>
        )}
      </ul>
      <Field label="New deployment label" hint={LABEL_RULE} error={error}>
        {(p) => (
          <div className="flex items-center gap-2">
            <Input
              {...p}
              className="font-mono"
              placeholder={placeholder}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  add();
                }
              }}
            />
            <Button onClick={add}>Add label</Button>
          </div>
        )}
      </Field>
    </div>
  );
}

/** "production and staging", "a, b and c". */
export const joinAnd = (items: string[]) =>
  items.length < 2 ? items.join("") : `${items.slice(0, -1).join(", ")} and ${items.at(-1)}`;

/** Where a label sits now, and what saving does to it, as the dialog and the new version form say it. */
export function labelHint(ticked: boolean, here: boolean, elsewhere: number | undefined): string | null {
  if (here) return ticked ? "On this version" : "Leaves this version";
  if (elsewhere !== undefined) return ticked ? `Moves from version ${elsewhere}` : `On version ${elsewhere}`;
  return null;
}

type Props = { promptId: string; version: number; labels: string[]; allLabels: LabelRef[]; latestVersion: number };

/**
 * The Deployment labels button and its dialog. `labels` may carry `latest`; it is shown, never set or sent. With
 * `promote`, the button is Promote to production and the dialog opens with production ticked.
 */
export function LabelEditor({ promote = false, ...props }: Props & { promote?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        size="sm"
        aria-haspopup="dialog"
        leadingIcon={promote ? undefined : <Tag size={13} strokeWidth={1.75} aria-hidden="true" />}
        onClick={() => setOpen(true)}
      >
        {promote ? "Promote to production" : "Deployment labels"}
      </Button>
      {open && <LabelDialog {...props} promote={promote} onClose={() => setOpen(false)} />}
    </>
  );
}

/** What agents that fetch production get next, and how to undo it. */
function promoteNote(version: number, from: number | undefined): string {
  const next = `Agents that fetch production get version ${version} on their next fetch. The SDKs cache a prompt for 60 seconds.`;
  if (from === undefined) return next;
  if (from > version) return `${next} Version ${from} does not change, so you can promote it again.`;
  return `${next} To roll back, move production back to version ${from}.`;
}

export function LabelDialog({
  promptId,
  version,
  labels,
  allLabels,
  latestVersion,
  promote,
  onClose,
}: Props & { promote: boolean; onClose: () => void }) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const own = labelsWithoutLatest(labels);
  const [value, setValue] = useState(promote && !own.includes("production") ? [...own, "production"] : own);
  const promoting = value.includes("production") && !own.includes("production");
  const unchanged = [...value].sort().join("\n") === [...own].sort().join("\n");
  const elsewhere = (label: string) => allLabels.find((l) => l.label === label && l.version !== version)?.version;

  const save = useMutation({
    mutationFn: (next: string[]) => api.setPromptVersionLabels(promptId, version, next),
    onSuccess: () => {
      invalidatePromptReads(qc, api.base, promptId);
      toast.success(promoting ? `Version ${version} is now production` : "Labels saved");
      onClose();
    },
    onError: (err) => toast.error("Could not save labels", (err as ApiError).message),
  });

  return (
    <Modal
      open
      onClose={onClose}
      title={`Deployment labels for version ${version}`}
      subtitle="An agent fetches the version that carries the label it asks for. A fetch with no label gets production."
      footer={
        <>
          <Button size="sm" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" variant="primary" disabled={unchanged} loading={save.isPending} onClick={() => save.mutate(value)}>
            {promoting ? "Promote to production" : "Save labels"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <LabelChecklist
          boxed
          value={value}
          options={allLabels.map((l) => l.label).concat(own)}
          onChange={setValue}
          rowHint={(label) => labelHint(value.includes(label), own.includes(label), elsewhere(label))}
          latest={{ checked: version === latestVersion }}
        />
        {promoting && (
          <p className="rounded-card bg-raised p-3 text-small text-fg-secondary">{promoteNote(version, elsewhere("production"))}</p>
        )}
        {own.includes("production") && !value.includes("production") && (
          <Note tone="warning">No version will carry production. Applications that fetch it get a not-found error.</Note>
        )}
      </div>
    </Modal>
  );
}
