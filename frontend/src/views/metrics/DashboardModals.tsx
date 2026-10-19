// SPDX-License-Identifier: Apache-2.0
// One modal at a time, remounted per state, so each opens seeded from what it acts on and never from the last one.
import { useState } from "react";
import { Button, Field, Input, Modal, Textarea } from "../../ui";
import { copyName } from "./metricsRules";
import type { MetricDashboardDefinition, MetricDashboardSummary } from "./wire";

export type ModalState =
  | null
  | { kind: "create" }
  | { kind: "rename"; dashboard: MetricDashboardSummary }
  | { kind: "delete"; dashboard: MetricDashboardSummary }
  | {
      kind: "clone";
      source: MetricDashboardSummary;
      definition: MetricDashboardDefinition | null;
      reason: "edit" | "duplicate";
    };

type Props = {
  state: ModalState;
  /** Existing dashboard names, so a copy is seeded with a free one. */
  taken: string[];
  /** A write is in flight: the primary action shows its spinner and takes no second click. */
  busy: boolean;
  onClose: () => void;
  onCreate: (name: string, description: string) => void;
  onRename: (id: string, name: string, description: string) => void;
  onDelete: (id: string) => void;
  onClone: (sourceId: string, name: string, definition: MetricDashboardDefinition | null) => void;
};

type Copy = { title: string; body?: string; action: string };

function copyOf(state: NonNullable<ModalState>): Copy {
  if (state.kind === "create") return { title: "New dashboard", action: "Create" };
  if (state.kind === "rename") return { title: "Rename dashboard", action: "Save changes" };
  if (state.kind === "delete") {
    return {
      title: "Delete this dashboard?",
      body: "This deletes the dashboard and its widgets. The traces, calls and cases it counted stay.",
      action: "Delete dashboard",
    };
  }
  return state.reason === "edit"
    ? {
        title: "Make an editable copy",
        body: "The default dashboard is built in. Your copy starts with the change you just made.",
        action: "Make a copy",
      }
    : { title: "Duplicate dashboard", body: "The copy starts with the same widgets.", action: "Duplicate" };
}

function seedName(state: NonNullable<ModalState>, taken: string[]): string {
  if (state.kind === "rename") return state.dashboard.name;
  if (state.kind === "clone") return copyName(state.source.name, taken);
  return "";
}

function DashboardModal({ state, taken, busy, onClose, onCreate, onRename, onDelete, onClone }: Props & {
  state: NonNullable<ModalState>;
}) {
  const [name, setName] = useState(() => seedName(state, taken));
  const [description, setDescription] = useState(state.kind === "rename" ? state.dashboard.description : "");
  const copy = copyOf(state);
  const named = state.kind !== "delete";
  const described = state.kind === "create" || state.kind === "rename";

  const submit = () => {
    const n = name.trim();
    const d = description.trim();
    if (state.kind === "create") onCreate(n, d);
    else if (state.kind === "rename") onRename(state.dashboard.id, n, d);
    else if (state.kind === "delete") onDelete(state.dashboard.id);
    else onClone(state.source.id, n, state.definition);
  };

  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title={copy.title}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant={state.kind === "delete" ? "danger" : "primary"}
            loading={busy}
            aria-busy={busy}
            disabled={named && name.trim() === ""}
            onClick={submit}
          >
            {copy.action}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {copy.body && <p className="text-small text-muted">{copy.body}</p>}
        {named && (
          <Field label="Name" required>
            {(p) => <Input {...p} maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />}
          </Field>
        )}
        {described && (
          <Field label="Description">
            {(p) => (
              <Textarea {...p} rows={2} maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} />
            )}
          </Field>
        )}
      </div>
    </Modal>
  );
}

export function DashboardModals(props: Props) {
  const { state } = props;
  if (state == null) return null;
  const key = state.kind === "create" ? "create" : `${state.kind}:${state.kind === "clone" ? state.source.id : state.dashboard.id}`;
  return <DashboardModal key={key} {...props} state={state} />;
}
