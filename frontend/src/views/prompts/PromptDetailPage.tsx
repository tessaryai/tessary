// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import type { ApiError, PromptDetail, PromptVersionView } from "../../api/types";
import { relativeTime } from "../../lib/relativeTime";
import { searchPatch } from "../../lib/searchPatch";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import {
  Badge, Button, Card, EmptyState, ErrorNote, LoadingRow, Modal, PageBody, PageHeader, SegmentedControl, useToast,
} from "../../ui";
import { RawJsonView } from "../traces/detail-json";
import { LabelEditor } from "./LabelEditor";
import { LinkedTracesPanel } from "./LinkedTracesPanel";
import { PlaygroundPanel } from "./PlaygroundPanel";
import { LabelBadge } from "./promptBits";
import { PromptBodyView } from "./PromptBodyView";
import { folderCrumbs, folderOf, labelsWithoutLatest, leafName } from "./promptFolders";
import { invalidatePromptReads, promptKeys, usePrompt, usePromptVersion } from "./promptReads";
import { TagsEditor } from "./TagsEditor";
import { UsagePanel } from "./UsagePanel";
import { defaultPair, VersionDiffModal } from "./VersionDiffModal";
import { VersionEditorModal } from "./VersionEditorModal";
import { VersionList } from "./VersionList";

const TABS = [
  { value: "body", label: "Body" },
  { value: "config", label: "Config" },
  { value: "usage", label: "Usage" },
  { value: "traces", label: "Traces" },
  { value: "playground", label: "Playground" },
];

/** The URL's version when the prompt has it, else the newest. */
function selectedVersion(detail: PromptDetail, raw: string | null): number {
  const n = Number(raw);
  return detail.versions.some((v) => v.version === n) ? n : detail.latest_version;
}

export function PromptDetailPage() {
  const { promptId } = useParams() as { promptId: string };
  const { orgSlug, projectSlug } = useTenant();
  const [params] = useSearchParams();
  const prompt = usePrompt(promptId);
  const selected = prompt.data ? selectedVersion(prompt.data, params.get("version")) : 0;
  const version = usePromptVersion(promptId, selected, prompt.isSuccess);
  const promptsPath = `/orgs/${orgSlug}/projects/${projectSlug}/prompts`;

  if (prompt.isPending) {
    return (
      <PageBody>
        <LoadingRow label="Loading prompt…" />
      </PageBody>
    );
  }
  if (prompt.isError) {
    return (
      <PageBody>
        {(prompt.error as ApiError).status === 404 ? (
          <EmptyState
            title="Prompt not found"
            body="It may have been deleted."
            action={
              <Link to={promptsPath} className="text-body text-link hover:text-link-hover">
                Back to prompts
              </Link>
            }
          />
        ) : (
          <ErrorNote error={prompt.error} />
        )}
      </PageBody>
    );
  }
  return <PromptView detail={prompt.data} selected={selected} version={version} promptsPath={promptsPath} />;
}

function PromptView({
  detail,
  selected,
  version,
  promptsPath,
}: {
  detail: PromptDetail;
  selected: number;
  version: ReturnType<typeof usePromptVersion>;
  promptsPath: string;
}) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const patch = searchPatch(params, setParams);
  const [editing, setEditing] = useState(false);
  const [comparing, setComparing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const closeDelete = () => setDeleting(false);
  const rawTab = params.get("tab");
  const tab = TABS.some((t) => t.value === rawTab) ? (rawTab as string) : "body";
  const folder = folderOf(detail.name);
  const v = version.data;

  const deletePrompt = useMutation({
    mutationFn: () => api.deletePrompt(detail.id),
    onSuccess: () => {
      qc.removeQueries({ queryKey: ["prompt", api.base, detail.id] });
      qc.removeQueries({ queryKey: ["prompt-version", api.base, detail.id] });
      invalidatePromptReads(qc, api.base);
      toast.success("Prompt deleted");
      navigate(`${promptsPath}?folder=${encodeURIComponent(folder)}`);
    },
    onError: (err) => toast.error("Could not delete prompt", (err as ApiError).message),
  });
  const saveTags = useMutation({
    mutationFn: (tags: string[]) => api.updatePromptTags(detail.id, tags),
    onSuccess: () => {
      invalidatePromptReads(qc, api.base, detail.id);
      toast.success("Tags saved");
    },
    onError: (err) => toast.error("Could not save tags", (err as ApiError).message),
  });

  const pane = () => {
    if (version.isPending) return <LoadingRow label="Loading version…" />;
    if (version.isError) return <ErrorNote error={version.error} />;
    const view = version.data;
    return (
      <>
        <VersionStrip detail={detail} view={view} />
        <SegmentedControl ariaLabel="Version view" value={tab} onChange={(t) => patch({ tab: t })} options={TABS} />
        <div>
          {tab === "body" && <PromptBodyView type={detail.type} body={view.body} />}
          {tab === "config" &&
            (Object.keys(view.config).length === 0 ? (
              <p className="text-small text-muted">No config on this version.</p>
            ) : (
              <RawJsonView value={view.config} fileName={`${leafName(detail.name)}.v${view.version}.config.json`} foldDepth={2} />
            ))}
          {tab === "usage" && <UsagePanel promptId={detail.id} versions={detail.versions} />}
          {tab === "traces" && <LinkedTracesPanel name={detail.name} version={view.version} />}
          {tab === "playground" && (
            <PlaygroundPanel promptId={detail.id} version={view.version} type={detail.type} body={view.body} config={view.config} />
          )}
        </div>
      </>
    );
  };

  return (
    <PageBody>
      <PageHeader
        className="mb-3"
        breadcrumb={[
          { label: "Prompts", to: promptsPath },
          ...folderCrumbs(folder).map((c) => ({ label: c.label, to: `${promptsPath}?folder=${encodeURIComponent(c.folder)}` })),
          { label: leafName(detail.name) },
        ]}
        title={<span className="font-mono">{detail.name}</span>}
        actions={
          <>
            <Button disabled={!v} onClick={() => setEditing(true)}>
              New version
            </Button>
            <Button variant="danger" onClick={() => setDeleting(true)}>
              Delete prompt
            </Button>
          </>
        }
      />
      <div className="mb-6">
        <TagsEditor tags={detail.tags} onSave={(tags) => saveTags.mutateAsync(tags)} />
      </div>
      <div className="grid grid-cols-[272px_minmax(0,1fr)] items-start gap-5">
        <VersionList
          versions={detail.versions}
          selected={selected}
          onSelect={(n) => patch({ version: String(n) })}
          onCompare={() => setComparing(true)}
        />
        <Card className="flex min-w-0 flex-col gap-3 border border-border p-4">{pane()}</Card>
      </div>
      {v && (
        <VersionEditorModal
          open={editing}
          promptId={detail.id}
          name={detail.name}
          type={detail.type}
          initialBody={v.body}
          initialConfig={v.config}
          onClose={() => setEditing(false)}
        />
      )}
      {comparing && (
        <VersionDiffModal prompt={detail} {...defaultPair(detail.versions, selected)} onClose={() => setComparing(false)} />
      )}
      {deleting && (
        <Modal
          open
          onClose={closeDelete}
          size="sm"
          title={`Delete ${leafName(detail.name)}?`}
          footer={
            <>
              <Button size="sm" variant="ghost" onClick={closeDelete}>
                Cancel
              </Button>
              <Button size="sm" variant="danger" loading={deletePrompt.isPending} onClick={() => deletePrompt.mutate()}>
                Delete prompt
              </Button>
            </>
          }
        >
          <p className="text-body text-muted">
            This permanently deletes every version and deployment label of this prompt. Applications that fetch it get a
            not-found error.
          </p>
        </Modal>
      )}
    </PageBody>
  );
}

function VersionStrip({ detail, view }: { detail: PromptDetail; view: PromptVersionView }) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const [confirming, setConfirming] = useState(false);
  const closeConfirm = () => setConfirming(false);
  const own = labelsWithoutLatest(view.labels);
  const blocked =
    own.length > 0
      ? "Move its deployment labels to another version first."
      : detail.versions.length === 1
        ? "Delete the prompt instead."
        : null;

  const remove = useMutation({
    mutationFn: () => api.deletePromptVersion(detail.id, view.version),
    onSuccess: () => {
      // Until the refetch lands, the cached latest_version is the number just deleted, which the page would re-read.
      qc.setQueryData<PromptDetail>(promptKeys.detail(api.base, detail.id), (d) => {
        if (!d) return d;
        const versions = d.versions.filter((x) => x.version !== view.version);
        return { ...d, versions, latest_version: Math.max(...versions.map((x) => x.version)) };
      });
      invalidatePromptReads(qc, api.base, detail.id);
      closeConfirm();
      searchPatch(params, setParams)({ version: null });
      toast.success(`Version ${view.version} deleted`);
    },
    onError: (err) => toast.error("Could not delete version", (err as ApiError).message),
  });

  return (
    <div className="flex items-start justify-between gap-4">
      <div className="flex min-w-0 flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-h2 text-fg">Version {view.version}</h2>
          {own.map((l) => (
            <LabelBadge key={l} label={l} text={l} />
          ))}
          {view.version === detail.latest_version && <Badge>latest</Badge>}
        </div>
        <p className="text-small text-muted">
          {`${view.commit_message ?? "No commit message"} · created by ${view.created_by_label} via ${view.created_via.toUpperCase()} · ${relativeTime(view.created_at)}`}
        </p>
      </div>
      <div className="flex shrink-0 flex-col items-end gap-1.5">
        <div className="flex items-center gap-2">
          <LabelEditor promptId={detail.id} version={view.version} labels={view.labels} allLabels={detail.labels} />
          <Button size="sm" variant="danger" disabled={blocked !== null} onClick={() => setConfirming(true)}>
            Delete version {view.version}
          </Button>
        </div>
        {blocked && <p className="text-small text-fg-secondary">{blocked}</p>}
      </div>
      {confirming && (
        <Modal
          open
          onClose={closeConfirm}
          size="sm"
          title={`Delete version ${view.version}?`}
          footer={
            <>
              <Button size="sm" variant="ghost" onClick={closeConfirm}>
                Cancel
              </Button>
              <Button size="sm" variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}>
                Delete version {view.version}
              </Button>
            </>
          }
        >
          <p className="text-body text-muted">
            This permanently deletes the version. Spans that name it keep the number and show as an unknown version.
          </p>
        </Modal>
      )}
    </div>
  );
}
