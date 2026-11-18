// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Folder, Plus, Trash } from "lucide-react";
import type { ApiError, FolderSummary, PromptSummary } from "../../api/types";
import { relativeTime } from "../../lib/relativeTime";
import { searchPatch } from "../../lib/searchPatch";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import {
  ActionMenu, Badge, Button, EmptyState, ErrorNote, Input, Modal, PageBody, PageHeader, Select, Spinner, Table,
  TableSkeleton, TBody, TD, TH, THead, TR, useToast,
} from "../../ui";
import { orderLabels } from "./LabelEditor";
import { LabelBadge } from "./promptBits";
import { folderCrumbs, leafName, parentFolder } from "./promptFolders";
import { invalidatePromptReads, usePromptFacets, usePromptLevel, usePromptSearch, usePromptUsageSummary } from "./promptReads";

export const PROMPTS_API_DOCS = "https://docs.tessary.ai/reference/prompts-api";

const DATE = "whitespace-nowrap font-mono text-small text-muted";
const SUBTITLE = "An agent fetches a prompt by name. Move the production deployment label to change the version it gets.";

const plural = (n: number, one: string) => `${n.toLocaleString("en-US")} ${one}${n === 1 ? "" : "s"}`;

/** "4 prompts and 1 folder in support." The counts are the server's, so a long list is never read as complete. */
function countLine(prompts: number, folders: number, folder: string): string {
  const parts = [plural(prompts, "prompt"), ...(folders > 0 ? [plural(folders, "folder")] : [])].join(" and ");
  return `${parts}${folder ? ` in ${folder}` : ""}. A / in a prompt name puts the prompt in a folder.`;
}

export function PromptsPage() {
  const api = useProjectApi();
  const { orgSlug, projectSlug } = useTenant();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const patch = searchPatch(params, setParams);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const closeDelete = () => setConfirmingDelete(false);

  const folder = params.get("folder") ?? "";
  const q = params.get("q") ?? "";
  const label = params.get("label") ?? "";
  const tag = params.get("tag") ?? "";
  const searching = q !== "";
  const promptsPath = `/orgs/${orgSlug}/projects/${projectSlug}/prompts`;
  const folderHref = (path: string) => `${promptsPath}?folder=${encodeURIComponent(path)}`;
  const newPrompt = () => navigate(`${promptsPath}/new${folder ? `?folder=${encodeURIComponent(folder)}` : ""}`);

  const narrow = { label: label || undefined, tag: tag || undefined };
  const level = usePromptLevel({ folder: folder || undefined, ...narrow }, !searching);
  const search = usePromptSearch({ q, ...narrow }, searching);
  const list = searching ? search : level;
  const facets = usePromptFacets();
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

  const { hasNextPage, isFetchingNextPage, fetchNextPage, isPlaceholderData } = list;
  const canLoadMore = hasNextPage && !isPlaceholderData;
  const loadMore = useCallback(() => {
    if (!isFetchingNextPage) void fetchNextPage();
  }, [isFetchingNextPage, fetchNextPage]);
  const sentinel = useInfiniteScroll(loadMore, canLoadMore);

  const calls = (name: string) =>
    usage.isPending ? (
      <span title="Counting calls">…</span>
    ) : usage.isError ? (
      "—"
    ) : (
      (callsByName.get(name) ?? 0).toLocaleString("en-US")
    );

  const folderRow = (f: FolderSummary) => (
    <TR key={`folder:${f.path}`}>
      <TD colSpan={7}>
        <span className="flex items-center gap-2">
          <Folder size={14} strokeWidth={1.75} aria-hidden="true" className="shrink-0 text-subtle" />
          <Link to={folderHref(f.path)} className="text-fg hover:text-link">
            {f.name}
          </Link>
          <span className="text-small text-muted">· {plural(f.prompt_count, "prompt")}</span>
        </span>
      </TD>
    </TR>
  );

  const promptRow = (p: PromptSummary) => (
    <TR key={p.id}>
      <TD>
        <Link to={`${promptsPath}/${encodeURIComponent(p.id)}`} className="text-fg hover:text-link">
          {searching ? p.name : leafName(p.name)}
        </Link>
      </TD>
      <TD>
        <Badge>{p.type}</Badge>
      </TD>
      <TD className="font-mono text-small text-fg-secondary">v{p.latest_version}</TD>
      <TD>
        {p.labels.length === 0 ? (
          <span className="text-small text-subtle">No labels</span>
        ) : (
          <span className="flex flex-wrap gap-1">
            {p.labels.map((l) => (
              <LabelBadge key={l.label} label={l.label} text={`${l.label} · v${l.version}`} />
            ))}
          </span>
        )}
      </TD>
      <TD className="text-right font-mono tabular-nums text-fg-secondary">{calls(p.name)}</TD>
      <TD>
        <span className="flex flex-wrap gap-1">
          {p.tags.map((t) => (
            <Badge key={t}>{t}</Badge>
          ))}
        </span>
      </TD>
      <TD className={DATE}>{relativeTime(p.updated_at)}</TD>
    </TR>
  );

  const content = () => {
    if (list.isPending) return <TableSkeleton rows={6} cols={7} />;
    if (list.isError) return <ErrorNote error={list.error} />;
    const first = list.data.pages[0];
    const folders = searching || !level.data ? [] : level.data.pages[0].folders;
    const prompts = list.data.pages.flatMap((page) => page.items);
    if (prompts.length === 0 && folders.length === 0) {
      if (q || label || tag) {
        return (
          <EmptyState
            title="No prompts match these filters"
            body="Change or clear the filters to see more prompts."
            action={<Button onClick={() => patch({ q: null, label: null, tag: null })}>Clear filters</Button>}
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
        <div className="rounded-card border border-border bg-surface">
          <EmptyState
            title="No prompts yet"
            body="Create a prompt on this page. You can also create prompts with the prompts API or the SDKs."
            action={
              <>
                <Button leadingIcon={<Plus size={14} strokeWidth={1.75} aria-hidden="true" />} onClick={newPrompt}>
                  New prompt
                </Button>
                <a href={PROMPTS_API_DOCS} target="_blank" rel="noreferrer" className="text-body text-link hover:text-link-hover">
                  Read the prompts API reference
                </a>
              </>
            }
          />
        </div>
      );
    }
    return (
      <>
        <div className="overflow-hidden rounded-card border border-border bg-surface">
          <Table>
            <THead>
              <TR>
                <TH>Name</TH>
                <TH>Type</TH>
                <TH>Latest</TH>
                <TH>Deployment labels</TH>
                <TH className="text-right">Calls (7 days)</TH>
                <TH>Tags</TH>
                <TH>Updated</TH>
              </TR>
            </THead>
            <TBody>
              {folders.map(folderRow)}
              {prompts.map(promptRow)}
            </TBody>
          </Table>
        </div>
        {/* The button is the keyboard and screen-reader path to the same action: you cannot tab into an intersection. */}
        {canLoadMore && (
          <div ref={sentinel} className="flex items-center justify-center pt-4">
            {isFetchingNextPage ? (
              <span className="flex items-center gap-2 text-small text-muted">
                <Spinner size="sm" /> Loading more prompts…
              </span>
            ) : (
              <Button variant="ghost" size="sm" onClick={loadMore}>
                Load more prompts
              </Button>
            )}
          </div>
        )}
        <p className="mt-3 text-small text-muted">
          {searching
            ? `${plural(first.total, "prompt")} match this search.`
            : countLine(first.total, folders.length, folder)}
        </p>
        {usage.isError && <ErrorNote className="mt-3" error={usage.error} />}
      </>
    );
  };

  const labelOptions = orderLabels([...(facets.data?.labels ?? []), label].filter(Boolean));
  const tagOptions = [...new Set([...(facets.data?.tags ?? []), tag].filter(Boolean))].sort();

  return (
    <PageBody>
      <PageHeader
        title="Prompts"
        subtitle={SUBTITLE}
        breadcrumb={
          folder
            ? [{ label: "Prompts", to: promptsPath }, ...folderCrumbs(folder).map((c) => ({ label: c.label, to: folderHref(c.folder) }))]
            : undefined
        }
        actions={
          <>
            <Button
              variant="primary"
              leadingIcon={<Plus size={14} strokeWidth={1.75} aria-hidden="true" />}
              onClick={newPrompt}
            >
              New prompt
            </Button>
            {folder && (
              <ActionMenu
                label="More actions for this folder"
                items={[
                  {
                    label: "Delete folder",
                    danger: true,
                    icon: <Trash size={14} strokeWidth={1.75} aria-hidden="true" />,
                    onSelect: () => setConfirmingDelete(true),
                  },
                ]}
              />
            )}
          </>
        }
      />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input
          key={q}
          aria-label="Search prompts by name"
          placeholder="Search by name"
          className="w-[320px]"
          defaultValue={q}
          onKeyDown={(e) => {
            if (e.key === "Enter") patch({ q: e.currentTarget.value.trim() || null });
          }}
        />
        <div className="w-[160px]">
          <Select aria-label="Deployment label" value={label} onChange={(e) => patch({ label: e.target.value || null })}>
            <option value="">Any label</option>
            {labelOptions.map((l) => (
              <option key={l} value={l}>
                {l}
              </option>
            ))}
          </Select>
        </div>
        <div className="w-[140px]">
          <Select aria-label="Tag" value={tag} onChange={(e) => patch({ tag: e.target.value || null })}>
            <option value="">Any tag</option>
            {tagOptions.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </Select>
        </div>
      </div>
      {content()}
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

/** `rootMargin` fires a screen early, so rows are in place before the reader arrives. */
function useInfiniteScroll(onHit: () => void, enabled: boolean) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = ref.current;
    if (!node || !enabled) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) onHit();
      },
      { rootMargin: "600px 0px" },
    );
    io.observe(node);
    return () => io.disconnect();
  }, [onHit, enabled]);
  return ref;
}
