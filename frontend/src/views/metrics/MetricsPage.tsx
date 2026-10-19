// SPDX-License-Identifier: Apache-2.0
/*
 * The URL holds the window and the call-site filter; edit mode holds a draft of the whole definition, which Done
 * writes in one PUT (or, on the built-in dashboard, as the definition of a copy). Every navigation builds an absolute
 * path from the project base: a relative one resolves against /metrics/:dashboardId and nests the segment.
 */
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { LayoutGrid, X } from "lucide-react";
import type { ApiError } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, EmptyState, ErrorNote, IconButton, PageHeader, Skeleton, useToast } from "../../ui";
import { LG_QUERY, useMediaQuery } from "../../ui/useMediaQuery";
import { DashboardGrid } from "./DashboardGrid";
import { DashboardModals, type ModalState } from "./DashboardModals";
import { DashboardSwitcher } from "./DashboardSwitcher";
import { WidgetEditor } from "./editor/WidgetEditor";
import { catalogQuery, dashboardQuery, dashboardsQuery, widgetQuery } from "./metricsQueries";
import {
  parseRange, rangeLabel, rangeParams, type RangeState, resolveWindow, unpricedTotal, viewOf, widgetRequest,
} from "./metricsRules";
import { RangeControl } from "./RangeControl";
import { WidgetCard, type WidgetState } from "./WidgetCard";
import type {
  MetricDashboard, MetricDashboardDefinition, MetricDashboardSummary, MetricDashboardUpdateRequest,
  MetricWidgetDefinition,
} from "./wire";

/** Mirrors catalog.limits.max_widgets; the toolbar copy names the number. */
const MAX_WIDGETS = 12;
const DEFAULT_ID = "default";

type EditorState = null | { mode: "add" } | { mode: "edit"; id: string };
type Update = { id: string; body: MetricDashboardUpdateRequest; kind: "save" | "rename" };

const summaryOf = (d: MetricDashboard): MetricDashboardSummary => ({
  id: d.id, name: d.name, description: d.description, built_in: d.built_in, widget_count: d.definition.widgets.length,
  updated_at: d.updated_at,
});

function LoadingTiles() {
  return (
    <>
      <span role="status" className="sr-only">
        Loading metrics
      </span>
      <div className="grid grid-cols-3 gap-4" aria-hidden="true">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-[248px] p-4 rounded-card bg-surface border border-border flex flex-col gap-2.5">
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-8 w-28" />
            <Skeleton className="h-3 w-40" />
          </div>
        ))}
      </div>
    </>
  );
}

/** Keyed by the dashboard id, so a draft, an open editor or a modal never outlives the dashboard it was made on. */
export function MetricsPage() {
  const id = useParams().dashboardId ?? DEFAULT_ID;
  return <DashboardPage key={id} id={id} />;
}

function DashboardPage({ id }: { id: string }) {
  const { orgSlug, projectSlug, api } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const qc = useQueryClient();
  const toast = useToast();
  const lg = useMediaQuery(LG_QUERY);

  const listQ = useQuery(dashboardsQuery(api));
  const dashQ = useQuery(dashboardQuery(api, id));
  const catalogQ = useQuery(catalogQuery(api));
  const dashboard = dashQ.data;
  const catalog = catalogQ.data;

  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<MetricDashboardDefinition | null>(null);
  const [editor, setEditor] = useState<EditorState>(null);
  const [modal, setModal] = useState<ModalState>(null);
  const addRef = useRef<HTMLButtonElement>(null);

  // Below lg the toolbar, Done and Cancel are gone, so edit mode cannot be left from the page; it ends here instead.
  useEffect(() => {
    if (lg) return;
    setEditing(false);
    setDraft(null);
    setEditor(null);
  }, [lg]);

  const range = parseRange(params, dashboard?.definition.default_range ?? "7d");
  // A preset's end moves once an hour, so reading the clock each render keeps every query key stable in between.
  const window = resolveWindow(range, Date.now());
  const callSite = params.get("call_site");
  const widgets = draft?.widgets ?? dashboard?.definition.widgets ?? [];
  const canManage = dashboard?.can_manage ?? listQ.data?.can_manage ?? false;
  const canEdit = canManage && lg && dashboard != null;
  const atCap = widgets.length >= MAX_WIDGETS;

  // Gated on the catalog: without it no call-site filter attaches, and every key changes once it arrives.
  const reads = useQueries({
    queries: widgets.map((w) => ({
      ...widgetQuery(api, widgetRequest(w, window, callSite, viewOf(catalog, w.view))),
      enabled: catalog != null,
    })),
  });
  const unpriced = unpricedTotal(reads.map((r) => r.data));

  const search = params.toString();
  const pathOf = (dashboardId: string) =>
    dashboardId === DEFAULT_ID ? `${basePath}/metrics` : `${basePath}/metrics/${encodeURIComponent(dashboardId)}`;
  const open = (dashboardId: string) => navigate(`${pathOf(dashboardId)}${search ? `?${search}` : ""}`);
  const invalidateList = () => qc.invalidateQueries({ queryKey: dashboardsQuery(api).queryKey });
  const leaveEditing = () => {
    setEditing(false);
    setDraft(null);
    setEditor(null);
  };

  const create = useMutation({
    mutationFn: (v: { name: string; description: string }) => api.createMetricDashboard(v),
    onSuccess: (created) => {
      toast.success("Dashboard created");
      invalidateList();
      setModal(null);
      open(created.id);
    },
    onError: (err) => toast.error("Could not create dashboard", (err as ApiError).message),
  });

  const clone = useMutation({
    mutationFn: (v: { sourceId: string; name: string; definition: MetricDashboardDefinition | null }) =>
      api.createMetricDashboard({ name: v.name, clone_of: v.sourceId, definition: v.definition }),
    onSuccess: (created) => {
      toast.success("Copy created");
      invalidateList();
      setModal(null);
      leaveEditing();
      open(created.id);
    },
    onError: (err) => toast.error("Could not create dashboard", (err as ApiError).message),
  });

  const update = useMutation({
    mutationFn: (v: Update) => api.updateMetricDashboard(v.id, v.body),
    onSuccess: (saved, v) => {
      toast.success(v.kind === "rename" ? "Dashboard renamed" : "Dashboard saved");
      qc.setQueryData(dashboardQuery(api, v.id).queryKey, saved);
      invalidateList();
      qc.invalidateQueries({ queryKey: dashboardQuery(api, v.id).queryKey });
      setModal(null);
      leaveEditing();
    },
    onError: (err) => toast.error("Could not save dashboard", (err as ApiError).message),
  });

  const remove = useMutation({
    mutationFn: (dashboardId: string) => api.deleteMetricDashboard(dashboardId),
    onSuccess: () => {
      toast.success("Dashboard deleted");
      invalidateList();
      setModal(null);
      navigate(`${basePath}/metrics`);
    },
    onError: (err) => toast.error("Could not delete dashboard", (err as ApiError).message),
  });

  const setRange = (next: RangeState) => {
    const p = rangeParams(next);
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        out.set("range", p.range);
        out.delete("from");
        out.delete("to");
        if (p.from && p.to) {
          out.set("from", p.from);
          out.set("to", p.to);
        }
        return out;
      },
      { replace: true },
    );
  };

  const clearCallSite = () =>
    setParams(
      (prev) => {
        const out = new URLSearchParams(prev);
        out.delete("call_site");
        return out;
      },
      { replace: true },
    );

  const startEditing = (d: MetricDashboard) => {
    setDraft(structuredClone(d.definition));
    setEditing(true);
  };

  const done = (d: MetricDashboard, next: MetricDashboardDefinition) => {
    if (d.built_in) setModal({ kind: "clone", source: summaryOf(d), definition: next, reason: "edit" });
    else update.mutate({ id: d.id, body: { name: d.name, description: d.description, definition: next }, kind: "save" });
  };

  const setWidgets = (next: MetricWidgetDefinition[]) => setDraft((d) => ({ ...d!, widgets: next }));

  const removeWidget = (widgetId: string) => {
    setWidgets(widgets.filter((w) => w.id !== widgetId));
    addRef.current!.focus();
  };

  const saveWidget = (w: MetricWidgetDefinition) => {
    setWidgets(editor?.mode === "edit" ? widgets.map((x) => (x.id === w.id ? w : x)) : [...widgets, w]);
    setEditor(null);
  };

  const stateOf = (i: number): WidgetState => {
    if (catalogQ.isError) return { status: "error", error: catalogQ.error, onRetry: () => void catalogQ.refetch() };
    const r = reads[i];
    if (r.isPending) return { status: "loading" };
    if (r.isError) return { status: "error", error: r.error, onRetry: () => void r.refetch() };
    return { status: "ready", result: r.data, stale: r.isPlaceholderData };
  };

  const notFound = (dashQ.error as ApiError | null)?.status === 404;

  return (
    <div className="pt-9 px-10 pb-14">
      <PageHeader
        kicker="Monitor"
        title="Metrics"
        subtitle={range.kind === "custom" ? rangeLabel(range) : undefined}
        actions={
          <>
            <RangeControl range={range} onChange={setRange} />
            {canEdit &&
              (editing ? (
                <Button variant="primary" loading={update.isPending} onClick={() => done(dashboard, draft!)}>
                  Done
                </Button>
              ) : (
                <Button variant="secondary" onClick={() => startEditing(dashboard)}>
                  Edit
                </Button>
              ))}
          </>
        }
      />

      {listQ.isError && <ErrorNote error={listQ.error} className="mb-4" />}
      {listQ.data && (
        <DashboardSwitcher
          dashboards={listQ.data.dashboards}
          currentId={id}
          canManage={canManage}
          disabled={editing}
          onSelect={open}
          onNew={() => setModal({ kind: "create" })}
          onRename={dashboard ? (d) => setModal({ kind: "rename", dashboard: d }) : undefined}
          onDuplicate={(d) => setModal({ kind: "clone", source: d, definition: null, reason: "duplicate" })}
          onDelete={(d) => setModal({ kind: "delete", dashboard: d })}
        />
      )}

      {editing && (
        <div
          role="group"
          aria-label="Edit dashboard"
          className="h-12 px-3 mb-4 flex items-center gap-2 rounded-card border border-dashed border-border-strong bg-surface"
        >
          <Button
            ref={addRef}
            size="sm"
            variant="secondary"
            aria-disabled={atCap || undefined}
            className={atCap ? "opacity-50 cursor-not-allowed" : undefined}
            onClick={() => {
              if (!atCap) setEditor({ mode: "add" });
            }}
          >
            Add widget
          </Button>
          <Button size="sm" variant="ghost" onClick={leaveEditing}>
            Cancel
          </Button>
          <span className="ml-auto text-small text-muted">
            {atCap ? "A dashboard holds at most 12 widgets." : "Drag a title bar to move a widget. Drag a corner to resize it."}
          </span>
        </div>
      )}

      {callSite != null && (
        <div className="mb-4 inline-flex items-center gap-1 rounded-pill bg-surface border border-border pl-2.5 pr-0.5 py-0.5 text-small text-fg">
          <span>Call site: {callSite}</span>
          <IconButton size="sm" label="Clear the call-site filter" onClick={clearCallSite}>
            <X size={12} strokeWidth={1.75} aria-hidden="true" />
          </IconButton>
        </div>
      )}

      {catalogQ.isError && <ErrorNote error={catalogQ.error} className="mb-4" />}

      {dashQ.isPending ? (
        <LoadingTiles />
      ) : notFound ? (
        <EmptyState
          title="Dashboard not found"
          body="It may have been deleted."
          action={<Button onClick={() => navigate(`${basePath}/metrics`)}>Open the default dashboard</Button>}
        />
      ) : dashQ.isError ? (
        <ErrorNote error={dashQ.error} />
      ) : widgets.length === 0 && !editing ? (
        <div className="rounded-card border border-dashed border-border-strong py-10">
          <EmptyState
            icon={<LayoutGrid size={32} strokeWidth={1.5} aria-hidden="true" />}
            title="No widgets yet"
            body="Add a widget to chart traces, LLM calls, tool calls or cases."
            action={
              canEdit && (
                <Button
                  variant="primary"
                  onClick={() => {
                    startEditing(dashQ.data);
                    setEditor({ mode: "add" });
                  }}
                >
                  Add widget
                </Button>
              )
            }
          />
        </div>
      ) : (
        <DashboardGrid
          widgets={widgets}
          editing={editing}
          stacked={!lg}
          renderWidget={(w, menu) => (
            <WidgetCard
              widget={w}
              view={viewOf(catalog, w.view)}
              state={stateOf(widgets.indexOf(w))}
              window={window}
              basePath={basePath}
              callSite={callSite}
              editing={editing}
              menu={menu}
            />
          )}
          onPlacements={setWidgets}
          onEdit={(widgetId) => setEditor({ mode: "edit", id: widgetId })}
          onRemove={removeWidget}
        />
      )}

      {unpriced && (
        <p className="text-muted mt-6 pt-4 border-t border-border text-small">
          {`${unpriced.count.toLocaleString("en-US")} ${unpriced.noun} ran on a model with no price on file. Their spend is left out, not counted as free. `}
          <Link to={`${basePath}/settings/models`} className="text-link hover:text-link-hover hover:underline">
            Review model settings
          </Link>
          .
        </p>
      )}

      {catalog && (
        <WidgetEditor
          open={editor != null}
          mode={editor?.mode ?? "add"}
          widget={editor?.mode === "edit" ? (widgets.find((w) => w.id === editor.id) ?? null) : null}
          catalog={catalog}
          widgets={widgets}
          range={range}
          window={window}
          callSite={callSite}
          basePath={basePath}
          onSave={saveWidget}
          onClose={() => setEditor(null)}
        />
      )}

      <DashboardModals
        state={modal}
        taken={listQ.data?.dashboards.map((d) => d.name) ?? []}
        busy={create.isPending || clone.isPending || update.isPending || remove.isPending}
        onClose={() => setModal(null)}
        onCreate={(name, description) => create.mutate({ name, description })}
        onRename={(dashboardId, name, description) =>
          update.mutate({ id: dashboardId, body: { name, description }, kind: "rename" })
        }
        onDelete={(dashboardId) => remove.mutate(dashboardId)}
        onClone={(sourceId, name, definition) => clone.mutate({ sourceId, name, definition })}
      />
    </div>
  );
}
