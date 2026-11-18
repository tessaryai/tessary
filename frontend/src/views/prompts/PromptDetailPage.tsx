// SPDX-License-Identifier: Apache-2.0
import { useId, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Play, Plus, Trash } from "lucide-react";
import type { ApiError, PromptDetail, PromptVersionView, VersionSummary } from "../../api/types";
import { relativeTime } from "../../lib/relativeTime";
import { searchPatch } from "../../lib/searchPatch";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import {
  ActionMenu, Badge, Button, Card, EmptyState, ErrorNote, LoadingRow, Modal, PageBody, PageHeader, TabNav, useToast,
} from "../../ui";
import { joinAnd, LabelEditor } from "./LabelEditor";
import { LinkedTracesPanel } from "./LinkedTracesPanel";
import { NewVersionPanel, type VersionDraft } from "./NewVersionPanel";
import { PlaygroundPanel } from "./PlaygroundPanel";
import { LabelBadge } from "./promptBits";
import { PromptBodyView } from "./PromptBodyView";
import { extractVariables } from "./promptCompile";
import { folderCrumbs, folderOf, labelsWithoutLatest, leafName } from "./promptFolders";
import { invalidatePromptReads, promptKeys, useOlderVersions, usePrompt, usePromptVersion } from "./promptReads";
import { TagsEditor } from "./TagsEditor";
import { UsagePanel } from "./UsagePanel";
import { UsePromptPanel } from "./UsePromptPanel";
import { defaultPair, VersionCompare } from "./VersionCompare";
import { VersionList } from "./VersionList";

/**
 * The page's tabs. Compare is a view inside Versions; New version and the playground are focused editors with no
 * tabs, opened from the header.
 */
const SECTIONS = [
  { value: "versions", label: "Versions" },
  { value: "metrics", label: "Metrics" },
];
const TABS = [
  { value: "prompt", label: "Prompt" },
  { value: "use", label: "Use prompt" },
  { value: "traces", label: "Linked traces" },
];

/**
 * The URL's version when it may exist, else the newest. The version list is paged, so a number below the loaded
 * pages is trusted and read; a number the loaded pages cover but do not hold was deleted.
 */
function selectedVersion(detail: PromptDetail, loaded: VersionSummary[], coversAll: boolean, raw: string | null): number {
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 1 || n > detail.latest_version) return detail.latest_version;
  if (loaded.some((v) => v.version === n)) return n;
  const oldest = loaded.at(-1)?.version ?? 0;
  return coversAll || n > oldest ? detail.latest_version : n;
}

export function PromptDetailPage() {
  const { promptId } = useParams() as { promptId: string };
  const { orgSlug, projectSlug } = useTenant();
  const prompt = usePrompt(promptId);
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
  return <PromptView detail={prompt.data} promptsPath={promptsPath} />;
}

function PromptView({ detail, promptsPath }: { detail: PromptDetail; promptsPath: string }) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const patch = searchPatch(params, setParams);
  const [showOlder, setShowOlder] = useState(false);
  const older = useOlderVersions(detail, showOlder);
  const olderPages = older.data?.pages ?? [];
  const versions = [...detail.versions, ...olderPages.flatMap((p) => p.versions)];
  const hasOlder = olderPages.length > 0 ? olderPages[olderPages.length - 1].has_older_versions : detail.has_older_versions;
  const selected = selectedVersion(detail, versions, !hasOlder, params.get("version"));
  const location = useLocation();
  const rawSection = params.get("section");
  const section = (["metrics", "compare", "playground", "new"] as const).find((s) => s === rawSection) ?? "versions";
  const focused = section === "playground" || section === "new";
  /** The version New version starts from, which `?from=` names. */
  const rawFrom = Number(params.get("from"));
  const from = Number.isInteger(rawFrom) && rawFrom > 0 ? rawFrom : selected;
  const version = usePromptVersion(detail.id, section === "new" ? from : selected, section !== "compare");
  /** An edit handed over from the playground or New version, kept only for the version it started from. */
  const handed = (location.state as { draft?: VersionDraft } | null)?.draft;
  const draft = handed && handed.version === (section === "new" ? from : selected) ? handed : undefined;
  const [deleting, setDeleting] = useState(false);
  const closeDelete = () => setDeleting(false);
  const rawTab = params.get("tab");
  const tab = TABS.some((t) => t.value === rawTab) ? (rawTab as string) : "prompt";
  const folder = folderOf(detail.name);

  /** A link to this page with `kv` set (or removed when null) and every other parameter kept. */
  const hrefWith = (kv: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, val] of Object.entries(kv)) {
      if (val === null) next.delete(k);
      else next.set(k, val);
    }
    const qs = next.toString();
    return `${promptsPath}/${encodeURIComponent(detail.id)}${qs ? `?${qs}` : ""}`;
  };
  /** The versions view, leaving any editor or compare. */
  const versionsHref = (kv: Record<string, string | null> = {}) => hrefWith({ section: null, from: null, to: null, ...kv });

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

  const versionPane = () => {
    if (version.isPending) return <LoadingRow label="Loading version…" />;
    if (version.isError) return <ErrorNote error={version.error} />;
    const view = version.data;
    return (
      <>
        <VersionStrip detail={detail} view={view} promptsPath={promptsPath} onlyVersion={versions.length === 1 && !hasOlder} />
        <TabNav
          label="Version sections"
          variant="pill"
          value={tab}
          items={TABS.map((t) => ({ ...t, to: hrefWith({ tab: t.value === "prompt" ? null : t.value }) }))}
        />
        <div>
          {tab === "prompt" && <PromptTab detail={detail} view={view} />}
          {tab === "use" && <UsePromptPanel detail={detail} view={view} />}
          {tab === "traces" && <LinkedTracesPanel name={detail.name} version={view.version} />}
        </div>
      </>
    );
  };

  const body = () => {
    if (section === "metrics") {
      const at = (version: number, tab: string | null) => hrefWith({ section: null, version: String(version), tab });
      return (
        <UsagePanel
          promptId={detail.id}
          versions={detail.versions}
          links={{
            version: (n) => at(n, null),
            traces: (n) => at(n, "traces"),
            usePrompt: hrefWith({ section: null, tab: "use" }),
          }}
        />
      );
    }
    if (section === "compare") {
      const pair = defaultPair(versions, selected);
      const pick = (key: "from" | "to", fallback: number) => {
        const n = Number(params.get(key));
        return Number.isInteger(n) && n > 0 ? n : fallback;
      };
      return (
        <VersionCompare
          detail={detail}
          versions={versions}
          from={pick("from", pair.left)}
          to={pick("to", pair.right)}
          onPick={(a, b) => patch({ from: String(a), to: String(b) })}
          backHref={versionsHref()}
          metricsHref={versionsHref({ section: "metrics" })}
        />
      );
    }
    if (section === "playground" || section === "new") {
      if (version.isPending) return <LoadingRow label="Loading version…" />;
      if (version.isError) return <ErrorNote error={version.error} />;
      const view = version.data;
      if (section === "new") {
        return (
          <NewVersionPanel
            key={`${view.version}:${draft ? "draft" : "version"}`}
            detail={detail}
            versions={versions}
            from={view}
            onFrom={(n) => patch({ from: String(n) })}
            draft={draft}
            cancelHref={versionsHref()}
            playgroundHref={hrefWith({ section: "playground", from: null, version: String(view.version) })}
            onCreated={(v) => navigate(versionsHref({ version: String(v.version), tab: null }))}
          />
        );
      }
      return (
        <PlaygroundPanel
          key={`${view.version}:${draft ? "draft" : "version"}`}
          promptId={detail.id}
          version={view.version}
          versions={versions}
          onVersion={(n) => patch({ version: String(n) })}
          type={detail.type}
          body={draft?.body ?? view.body}
          config={draft?.config ?? view.config}
          onSaveAsVersion={(d) =>
            navigate(hrefWith({ section: "new", from: String(view.version) }), {
              state: { draft: { version: view.version, ...d } satisfies VersionDraft },
            })
          }
        />
      );
    }
    return (
      <div className="grid grid-cols-[260px_minmax(0,1fr)] items-start gap-4">
        <VersionList
          versions={versions}
          selected={selected}
          onSelect={(n) => patch({ version: String(n) })}
          hasOlder={hasOlder}
          loadingOlder={older.isFetching}
          onShowOlder={() => (showOlder ? void older.fetchNextPage() : setShowOlder(true))}
        />
        <Card className="flex min-w-0 flex-col gap-4 border border-border p-5">{versionPane()}</Card>
      </div>
    );
  };

  const crumbs = [
    { label: "Prompts", to: promptsPath },
    ...folderCrumbs(folder).map((c) => ({ label: c.label, to: `${promptsPath}?folder=${encodeURIComponent(c.folder)}` })),
  ];

  if (focused) {
    return (
      <PageBody>
        <PageHeader
          className="mb-4"
          breadcrumb={[
            ...crumbs,
            { label: leafName(detail.name), to: versionsHref() },
            { label: section === "new" ? "New version" : "Playground" },
          ]}
          title={<span className="font-mono">{detail.name}</span>}
          titleAside={<Badge>{detail.type}</Badge>}
        />
        {body()}
      </PageBody>
    );
  }

  return (
    <PageBody>
      <PageHeader
        className="mb-4"
        breadcrumb={[...crumbs, { label: leafName(detail.name) }]}
        title={<span className="font-mono">{detail.name}</span>}
        titleAside={
          <span className="flex flex-wrap items-center gap-1.5">
            <Badge>{detail.type}</Badge>
            <TagsEditor tags={detail.tags} onSave={(tags) => saveTags.mutateAsync(tags)} />
          </span>
        }
        actions={
          <>
            <Button
              disabled={versions.length < 2}
              onClick={() => {
                const pair = defaultPair(versions, selected);
                patch({ section: "compare", from: String(pair.left), to: String(pair.right) });
              }}
            >
              Compare versions
            </Button>
            <Button
              leadingIcon={<Play size={13} strokeWidth={1.75} aria-hidden="true" />}
              onClick={() => patch({ section: "playground", from: null, to: null })}
            >
              Playground
            </Button>
            <Button
              variant="primary"
              leadingIcon={<Plus size={14} strokeWidth={1.75} aria-hidden="true" />}
              onClick={() => patch({ section: "new", from: String(selected), to: null })}
            >
              New version
            </Button>
            <ActionMenu
              label="More actions for this prompt"
              items={[
                {
                  label: "Delete prompt",
                  danger: true,
                  icon: <Trash size={14} strokeWidth={1.75} aria-hidden="true" />,
                  onSelect: () => setDeleting(true),
                },
              ]}
            />
          </>
        }
      />
      <TabNav
        label="Prompt sections"
        className="mb-4"
        value={section === "metrics" ? "metrics" : "versions"}
        items={SECTIONS.map((s) => ({ ...s, to: versionsHref({ section: s.value === "versions" ? null : s.value }) }))}
      />
      {body()}
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

/** The prompt as stored, its variables, then its config. */
function PromptTab({ detail, view }: { detail: PromptDetail; view: PromptVersionView }) {
  const variablesId = useId();
  const variables = extractVariables(view.body);
  const hasConfig = Object.keys(view.config).length > 0;
  return (
    <div className="flex flex-col gap-4">
      <PromptBodyView type={detail.type} body={view.body} />
      {variables.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span id={variablesId} className="text-small text-muted">
            Variables
          </span>
          <ul aria-labelledby={variablesId} className="flex flex-wrap gap-1.5">
            {variables.map((name) => (
              <li key={name} className="rounded-micro border border-border bg-raised px-1.5 font-mono text-code text-fg-secondary">
                {name}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className="flex flex-col gap-2">
        <h3 className="text-h3 text-fg">Config</h3>
        {hasConfig ? (
          <pre className="m-0 overflow-x-auto rounded-card border border-border bg-bg p-3 font-mono text-code text-fg-secondary">
            {JSON.stringify(view.config, null, 2)}
          </pre>
        ) : (
          <p className="text-small text-muted">No config on this version.</p>
        )}
      </div>
    </div>
  );
}

function VersionStrip({
  detail,
  view,
  promptsPath,
  onlyVersion,
}: {
  detail: PromptDetail;
  view: PromptVersionView;
  promptsPath: string;
  onlyVersion: boolean;
}) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [confirming, setConfirming] = useState(false);
  const closeConfirm = () => setConfirming(false);
  const own = labelsWithoutLatest(view.labels);
  const meta = [
    view.commit_message ?? "No commit message",
    view.created_via === "api" ? `${view.created_by_label} via API` : view.created_by_label,
    relativeTime(view.created_at),
  ].join(" · ");

  const remove = useMutation({
    mutationFn: () => api.deletePromptVersion(detail.id, view.version),
    onSuccess: () => {
      if (onlyVersion) {
        // The server deletes a prompt with its last version.
        qc.removeQueries({ queryKey: ["prompt", api.base, detail.id] });
        qc.removeQueries({ queryKey: ["prompt-version", api.base, detail.id] });
        invalidatePromptReads(qc, api.base);
        toast.success("Prompt deleted");
        navigate(`${promptsPath}?folder=${encodeURIComponent(folderOf(detail.name))}`);
        return;
      }
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
        <p className="text-small text-muted">{meta}</p>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <LabelEditor
          promptId={detail.id}
          version={view.version}
          labels={view.labels}
          allLabels={detail.labels}
          latestVersion={detail.latest_version}
        />
        {!own.includes("production") && (
          <LabelEditor
            promote
            promptId={detail.id}
            version={view.version}
            labels={view.labels}
            allLabels={detail.labels}
            latestVersion={detail.latest_version}
          />
        )}
        <ActionMenu
          label={`More actions for version ${view.version}`}
          items={[
            {
              label: `Delete version ${view.version}`,
              danger: true,
              icon: <Trash size={14} strokeWidth={1.75} aria-hidden="true" />,
              onSelect: () => setConfirming(true),
            },
          ]}
        />
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
          <div className="flex flex-col gap-2 text-body text-muted">
            <p>This permanently deletes the version. Spans that name it keep the number and show as an unknown version.</p>
            {own.length === 1 && (
              <p>
                It carries the deployment label {own[0]}. A fetch by that label gets a not-found error until you move it
                to another version.
              </p>
            )}
            {own.length > 1 && (
              <p>
                It carries the deployment labels {joinAnd(own)}. A fetch by those labels gets a not-found error until you
                move them to another version.
              </p>
            )}
            {onlyVersion && <p>This is the only version, so deleting it also deletes the prompt.</p>}
          </div>
        </Modal>
      )}
    </div>
  );
}
