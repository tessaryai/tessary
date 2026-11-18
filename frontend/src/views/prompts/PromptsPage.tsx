// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { ChevronRight, Folder, Plus } from "lucide-react";
import type { ApiError, PromptType } from "../../api/types";
import { relativeTime } from "../../lib/relativeTime";
import { searchPatch } from "../../lib/searchPatch";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import {
  Badge, Button, EmptyState, ErrorNote, Input, Modal, PageBody, PageHeader, SegmentedControl, Select, Table,
  TableSkeleton, TBody, TD, TH, THead, TR, useToast,
} from "../../ui";
import { orderLabels } from "./LabelEditor";
import { NewPromptModal } from "./NewPromptModal";
import { LabelBadge } from "./promptBits";
import { folderCrumbs, groupByFolder, type ListEntry, parentFolder } from "./promptFolders";
import { invalidatePromptReads, usePromptList, usePromptUsageSummary } from "./promptReads";

const TYPES = [
  { value: "all", label: "All" },
  { value: "text", label: "Text" },
  { value: "chat", label: "Chat" },
];

const DATE = "font-mono text-small text-muted";

export function PromptsPage() {
  const api = useProjectApi();
  const { orgSlug, projectSlug } = useTenant();
  const qc = useQueryClient();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const patch = searchPatch(params, setParams);
  const [creating, setCreating] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const closeDelete = () => setConfirmingDelete(false);

  const folder = params.get("folder") ?? "";
  const q = params.get("q") ?? "";
  const label = params.get("label") ?? "";
  const type = params.get("type") ?? "";
  const promptsPath = `/orgs/${orgSlug}/projects/${projectSlug}/prompts`;

  const list = usePromptList({
    folder: folder || undefined,
    q: q || undefined,
    label: label || undefined,
    type: (type || undefined) as PromptType | undefined,
  });
  const usage = usePromptUsageSummary(7);
  const callsByName = new Map(usage.data?.items.map((i) => [i.name, i.calls]));

  const deleteFolder = useMutation({
    mutationFn: () => api.deletePromptFolder(folder),
    onSuccess: ({ deleted_count: n }) => {
      invalidatePromptReads(qc, api.base);
      closeDelete();
      patch({ folder: parentFolder(folder) });
      toast.success(n === 1 ? "Deleted 1 prompt" : `Deleted ${n} prompts`);
    },
    onError: (err) => toast.error("Could not delete folder", (err as ApiError).message),
  });

  const calls = (name: string) =>
    usage.isPending ? (
      <span title="Counting calls">…</span>
    ) : usage.isError ? (
      "—"
    ) : (
      (callsByName.get(name) ?? 0).toLocaleString("en-US")
    );

  const row = (entry: ListEntry) => {
    if (entry.kind === "folder") {
      const open = () => patch({ folder: entry.folder });
      return (
        <TR
          key={`folder:${entry.folder}`}
          interactive
          tabIndex={0}
          onClick={open}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              open();
            }
          }}
        >
          <TD>
            <span className="flex items-center gap-1.5">
              <ChevronRight size={12} strokeWidth={1.75} aria-hidden="true" className="text-subtle" />
              <Folder size={13} strokeWidth={1.75} aria-hidden="true" className="text-subtle" />
              <span>{entry.label}</span> <span className="text-muted">({entry.count})</span>
            </span>
          </TD>
          <TD />
          <TD />
          <TD />
          <TD />
          <TD className={DATE}>{relativeTime(entry.updatedAt)}</TD>
        </TR>
      );
    }
    const p = entry.prompt;
    return (
      <TR key={p.id}>
        <TD>
          <Link to={`${promptsPath}/${encodeURIComponent(p.id)}`} className="text-fg hover:text-link">
            {entry.label}
          </Link>
        </TD>
        <TD>
          <Badge>{p.type}</Badge>
        </TD>
        <TD className={DATE}>v{p.latest_version}</TD>
        <TD>
          {p.labels.length === 0 ? (
            <span className="text-small text-subtle">No labels</span>
          ) : (
            <span className="flex flex-wrap gap-1">
              {p.labels.map((l) => (
                <LabelBadge key={l.label} label={l.label} text={`${l.label} v${l.version}`} />
              ))}
            </span>
          )}
        </TD>
        <TD className="text-right font-mono tabular-nums text-fg-secondary">{calls(p.name)}</TD>
        <TD className={DATE}>{relativeTime(p.updated_at)}</TD>
      </TR>
    );
  };

  const content = () => {
    if (list.isPending) return <TableSkeleton rows={6} cols={6} />;
    if (list.isError) return <ErrorNote error={list.error} />;
    const { items, total } = list.data;
    if (items.length === 0) {
      if (q || label || type) {
        return (
          <EmptyState
            title="No prompts match these filters"
            body="Change or clear the filters to see more prompts."
            action={<Button onClick={() => patch({ q: null, label: null, type: null })}>Clear filters</Button>}
          />
        );
      }
      if (folder) {
        const parent = parentFolder(folder);
        return (
          <EmptyState
            title="No prompts in this folder"
            body={`A prompt is in this folder when its name starts with ${folder}/.`}
            action={<Button onClick={() => patch({ folder: parent })}>{`Back to ${parent ?? "prompts"}`}</Button>}
          />
        );
      }
      return (
        <EmptyState
          title="No prompts yet"
          body="Create a prompt to start."
          action={
            <Button variant="primary" onClick={() => setCreating(true)}>
              New prompt
            </Button>
          }
        />
      );
    }
    const entries: ListEntry[] = q ? items.map((p) => ({ kind: "prompt", label: p.name, prompt: p })) : groupByFolder(items, folder);
    return (
      <>
        <Table>
          <THead>
            <TR>
              <TH>Name</TH>
              <TH>Type</TH>
              <TH>Latest</TH>
              <TH>Labels</TH>
              <TH className="text-right">Calls (7 days)</TH>
              <TH>Updated</TH>
            </TR>
          </THead>
          <TBody>{entries.map(row)}</TBody>
        </Table>
        {total > items.length && (
          <p className="mt-3 text-small text-muted">{`Showing the first ${items.length} of ${total} prompts. Search to narrow the list.`}</p>
        )}
        {usage.isError && <ErrorNote className="mt-3" error={usage.error} />}
      </>
    );
  };

  const seenLabels = (list.data?.items ?? []).flatMap((p) => p.labels.map((l) => l.label));

  return (
    <PageBody>
      <PageHeader
        kicker="Build"
        title="Prompts"
        breadcrumb={
          folder
            ? [
                { label: "Prompts", to: promptsPath },
                ...folderCrumbs(folder).map((c) => ({ label: c.label, to: `${promptsPath}?folder=${encodeURIComponent(c.folder)}` })),
              ]
            : undefined
        }
        actions={
          <>
            {folder && (
              <Button variant="danger" onClick={() => setConfirmingDelete(true)}>
                Delete folder
              </Button>
            )}
            <Button
              variant="primary"
              leadingIcon={<Plus size={14} strokeWidth={1.75} aria-hidden="true" />}
              onClick={() => setCreating(true)}
            >
              New prompt
            </Button>
          </>
        }
      />
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Input
          key={q}
          aria-label="Search prompts"
          placeholder="Search prompts"
          className="w-[300px]"
          defaultValue={q}
          onKeyDown={(e) => {
            if (e.key === "Enter") patch({ q: e.currentTarget.value.trim() || null });
          }}
        />
        <SegmentedControl
          ariaLabel="Type"
          value={type || "all"}
          onChange={(v) => patch({ type: v === "all" ? null : v })}
          options={TYPES}
        />
        <Select
          aria-label="Deployment label"
          className="w-[160px]"
          value={label}
          onChange={(e) => patch({ label: e.target.value || null })}
        >
          <option value="">Any label</option>
          {orderLabels([...seenLabels, label].filter(Boolean)).map((l) => (
            <option key={l} value={l}>
              {l}
            </option>
          ))}
        </Select>
      </div>
      {content()}
      {creating && <NewPromptModal onClose={() => setCreating(false)} />}
      {confirmingDelete && (
        <Modal
          open
          onClose={closeDelete}
          size="sm"
          title={`Delete folder ${folder}?`}
          footer={
            <>
              <Button size="sm" variant="ghost" onClick={closeDelete}>
                Cancel
              </Button>
              <Button size="sm" variant="danger" loading={deleteFolder.isPending} onClick={() => deleteFolder.mutate()}>
                Delete folder
              </Button>
            </>
          }
        >
          <p className="text-body text-muted">
            This permanently deletes every prompt in this folder, with all versions and deployment labels. Applications
            that fetch them get a not-found error.
          </p>
        </Modal>
      )}
    </PageBody>
  );
}
