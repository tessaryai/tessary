// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ChevronDown } from "lucide-react";
import type { ApiError, LabelRef } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { Button, Input, Toggle, useToast } from "../../ui";
import { useDropdown } from "../../ui/useDropdown";
import { labelsWithoutLatest } from "./promptFolders";
import { invalidatePromptReads } from "./promptReads";
import { Note } from "./promptBits";

const LABEL = /^[a-z0-9_.-]{1,36}$/;

/** production and staging always first, then every other label alphabetical, each once. */
export function orderLabels(labels: string[]): string[] {
  const rest = [...new Set(labels)].filter((l) => l !== "production" && l !== "staging").sort();
  return ["production", "staging", ...rest];
}

/** One switch per label plus a box to add one; reused by the label panel and both version modals. */
export function LabelChecklist({
  value,
  options,
  onChange,
  rowHint,
}: {
  value: string[];
  options: string[];
  onChange: (next: string[]) => void;
  rowHint?: (label: string) => string | null;
}) {
  const [added, setAdded] = useState<string[]>([]);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const rows = orderLabels([...options, ...added, ...value]);

  const add = () => {
    const label = draft.trim();
    if (label === "latest") return setError("latest is derived from the highest version and cannot be set.");
    if (!LABEL.test(label)) return setError("Use 1 to 36 lowercase letters, digits, _ . or -.");
    if (rows.includes(label)) return setError("This label is already in the list.");
    setAdded([...added, label]);
    onChange(orderLabels([...rows, label]).filter((r) => r === label || value.includes(r)));
    setDraft("");
    setError(null);
  };

  return (
    <div className="flex flex-col">
      {rows.map((label) => {
        const hint = rowHint?.(label);
        return (
          <div key={label} className="flex items-start justify-between gap-3 px-3.5 py-2">
            <div className="min-w-0">
              <div className="font-mono text-small text-fg">{label}</div>
              {hint && <div className="text-small text-subtle">{hint}</div>}
            </div>
            <Toggle
              label={label}
              checked={value.includes(label)}
              onChange={(on) => onChange(rows.filter((r) => (r === label ? on : value.includes(r))))}
            />
          </div>
        );
      })}
      <div className="flex flex-col gap-1.5 px-3.5 py-2">
        <div className="flex items-center gap-2">
          <Input aria-label="New label" placeholder="New label" value={draft} onChange={(e) => setDraft(e.target.value)} />
          <Button size="sm" onClick={add}>
            Add label
          </Button>
        </div>
        {error && <Note tone="error">{error}</Note>}
      </div>
    </div>
  );
}

const PANEL =
  "absolute right-0 top-full mt-1 z-30 w-80 max-h-[60vh] overflow-y-auto rounded-card bg-overlay border border-border-strong py-1";

type Props = { promptId: string; version: number; labels: string[]; allLabels: LabelRef[] };

/** The Labels button and its panel. `labels` may carry `latest`; it is never shown, set or sent. */
export function LabelEditor(props: Props) {
  const { open, setOpen, ref } = useDropdown();
  return (
    <div ref={ref} className="relative">
      <Button
        size="sm"
        aria-haspopup="dialog"
        aria-expanded={open}
        trailingIcon={<ChevronDown size={13} strokeWidth={1.75} aria-hidden="true" />}
        onClick={() => setOpen(!open)}
      >
        Labels
      </Button>
      {open && <LabelPanel {...props} onClose={() => setOpen(false)} />}
    </div>
  );
}

function LabelPanel({ promptId, version, labels, allLabels, onClose }: Props & { onClose: () => void }) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const own = labelsWithoutLatest(labels);
  const [value, setValue] = useState(own);
  const promoting = value.includes("production") && !own.includes("production");
  const unchanged = [...value].sort().join("\n") === [...own].sort().join("\n");

  const save = useMutation({
    mutationFn: (next: string[]) => api.setPromptVersionLabels(promptId, version, next),
    onSuccess: () => {
      invalidatePromptReads(qc, api.base, promptId);
      toast.success(promoting ? `Version ${version} is now production` : "Labels saved");
      onClose();
    },
    onError: (err) => toast.error("Could not save labels", (err as ApiError).message),
  });

  const elsewhere = (label: string) => {
    const at = allLabels.find((l) => l.label === label && l.version !== version);
    return at && value.includes(label) ? `On version ${at.version}. Saving moves it here.` : null;
  };

  return (
    <div
      role="dialog"
      aria-label={`Deployment labels for version ${version}`}
      className={PANEL}
      style={{ boxShadow: "var(--shadow-md)" }}
    >
      <div className="px-3.5 pb-1 pt-2 font-mono text-label uppercase text-subtle">Deployment labels</div>
      <LabelChecklist
        value={value}
        options={allLabels.map((l) => l.label).concat(own)}
        onChange={setValue}
        rowHint={elsewhere}
      />
      {own.includes("production") && !value.includes("production") && (
        <div className="px-3.5 py-2">
          <Note tone="warning">No version will carry production. Applications that fetch it get a not-found error.</Note>
        </div>
      )}
      <div className="flex justify-end gap-2 border-t border-border px-3.5 pb-1.5 pt-2.5">
        <Button size="sm" variant="ghost" onClick={onClose}>
          Cancel
        </Button>
        <Button size="sm" variant="primary" disabled={unchanged} loading={save.isPending} onClick={() => save.mutate(value)}>
          {promoting ? "Promote to production" : "Save labels"}
        </Button>
      </div>
    </div>
  );
}
