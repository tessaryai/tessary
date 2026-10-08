// SPDX-License-Identifier: Apache-2.0
/*
 * Classifiers: what each classifier sees, charted per call site and per tool.
 *
 * The Call site section charts every classifier that is on for one call site; the Tool section charts Tool Errors
 * and tool-call Duration Drift for one tool across every call site, because the server keys those two on the tool,
 * not on the call site. One range drives both. A classifier with nothing to chart is named in a chip with the
 * reason, so a missing card never reads as a missing classifier. The open findings live on Triage, and each
 * classifier's switch and settings live on its configure page, which the Configure menu lists.
 */
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { ChartRange, ChartToolOption, ClassifierCharts } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { EmptyState, ErrorNote, LoadingRow, PageHeader, Section, SegmentedControl } from "../../ui";
import { CONTAINER } from "./shared";
import { FrustrationBanner } from "./FrustrationBanner";
import { FRUSTRATION_DETECTOR } from "./FrustrationEnableModal";
import { ChartCard } from "./ChartCard";
import { ConfigureMenu, ScopePicker } from "./ChartPickers";
import {
  callSiteMeta,
  callersText,
  chipText,
  defaultCallSite,
  defaultTool,
  rankCallSites,
  toolGroups,
  toolMeta,
} from "./chartRules";

const RANGES = [
  { value: "7", label: "7d" },
  { value: "28", label: "28d" },
  { value: "90", label: "90d" },
] as const;

type RangeValue = (typeof RANGES)[number]["value"];

export function ClassifiersPage() {
  const { api, orgSlug, projectSlug } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const [days, setDays] = useState<ChartRange>(28);
  const [pickedCallSite, setPickedCallSite] = useState<string | null>(null);
  const [pickedTool, setPickedTool] = useState<string | null>(null);

  const classifiersQ = useQuery({ queryKey: ["classifiers", api.base], queryFn: api.listClassifiers });
  const frustration = classifiersQ.data?.find((c) => c.detector === FRUSTRATION_DETECTOR);

  const scopesQ = useQuery({
    queryKey: ["classifier-chart-scopes", api.base, days],
    queryFn: () => api.getClassifierChartScopes(days),
    placeholderData: (prev) => prev,
  });
  const callSites = scopesQ.data?.call_sites ?? [];
  const tools = scopesQ.data?.tools ?? [];

  const callSite = callSites.some((c) => c.call_site_id === pickedCallSite) ? pickedCallSite : defaultCallSite(callSites);
  const tool = tools.some((t) => t.tool_key === pickedTool) ? pickedTool : defaultTool(tools, callSite);
  const toolOption = tools.find((t) => t.tool_key === tool) ?? null;

  const siteQ = useCharts("call_site", callSite, days);
  const toolQ = useCharts("tool", tool, days);

  const pickCallSite = (id: string) => {
    setPickedCallSite(id);
    setPickedTool(null);
  };

  const noTraffic = scopesQ.data != null && callSites.length === 0 && tools.length === 0;

  return (
    <div style={CONTAINER}>
      <PageHeader
        kicker="Monitor"
        title="Classifiers"
        actions={
          <>
            <SegmentedControl<RangeValue>
              ariaLabel="Time range"
              value={String(days) as RangeValue}
              onChange={(v) => setDays(Number(v) as ChartRange)}
              options={[...RANGES]}
            />
            <ConfigureMenu classifiers={scopesQ.data?.classifiers} error={scopesQ.error} basePath={basePath} />
          </>
        }
      />

      {frustration && <FrustrationBanner classifier={frustration} />}
      {scopesQ.isLoading && <LoadingRow />}
      {scopesQ.isError && <ErrorNote error={scopesQ.error} />}

      {noTraffic && (
        <EmptyState
          title="No traffic yet"
          body="Each call site and each tool gets its charts here once traces arrive."
        />
      )}

      {scopesQ.data && !noTraffic && (
        <>
          <Section
            title="Call site"
            actions={
              callSite && (
                <ScopePicker
                  label="Call site"
                  listLabel="Call sites"
                  searchLabel="Search call sites"
                  value={callSite}
                  selected={callSite}
                  onPick={pickCallSite}
                  groups={[{ options: rankCallSites(callSites).map((c) => ({ id: c.call_site_id, name: c.call_site_id, meta: callSiteMeta(c) })) }]}
                />
              )
            }
          >
            {callSites.length === 0 ? (
              <p className="text-body text-muted">No call sites yet.</p>
            ) : (
              <Charts query={siteQ} days={days} basePath={basePath} empty="Nothing to chart for this call site in this range." />
            )}
          </Section>

          <div className="border-t border-border pt-8">
            <Section
              title="Tool"
              subtitle={toolOption ? callersText(toolOption) : undefined}
              actions={
                toolOption && (
                  <ScopePicker
                    label="Tool"
                    listLabel="Tools"
                    value={toolOption.label}
                    selected={tool}
                    onPick={setPickedTool}
                    groups={toolGroups(tools, callSite).map((g) => ({
                      label: g.label,
                      options: g.tools.map((t: ChartToolOption) => ({ id: t.tool_key, name: t.label, meta: toolMeta(t) })),
                    }))}
                  />
                )
              }
            >
              {tools.length === 0 ? (
                <p className="text-body text-muted">No tool was called in this range.</p>
              ) : (
                <Charts query={toolQ} days={days} basePath={basePath} empty="Nothing to chart for this tool in this range." />
              )}
            </Section>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * The cards for one scope. A new range keeps the scope's old cards on screen while it reads, with a line above them
 * saying the new range is loading; a new scope does not, because one call site's charts under another's name would
 * mislead.
 */
function useCharts(scope: "call_site" | "tool", id: string | null, days: ChartRange) {
  const { api } = useTenant();
  return useQuery({
    queryKey: ["classifier-charts", api.base, scope, id, days],
    queryFn: () => api.getClassifierCharts(scope === "tool" ? { tool: id! } : { callSiteId: id! }, days),
    enabled: id != null,
    placeholderData: (prev, prevQuery) =>
      prevQuery?.queryKey[2] === scope && prevQuery.queryKey[3] === id ? prev : undefined,
  });
}

function Charts({
  query,
  days,
  basePath,
  empty,
}: {
  query: ReturnType<typeof useCharts>;
  days: ChartRange;
  basePath: string;
  empty: string;
}) {
  if (query.isLoading) return <LoadingRow />;
  if (query.isError) return <ErrorNote error={query.error} />;
  const data: ClassifierCharts | undefined = query.data;
  if (!data) return null;
  const stale = query.isPlaceholderData;
  const reading = stale && <LoadingRow className="mb-3" label={`Loading the last ${days} days…`} />;
  if (data.cards.length === 0 && data.chips.length === 0) {
    return (
      <>
        {reading}
        <p className="text-body text-muted">{empty}</p>
      </>
    );
  }
  return (
    <>
      {reading}
      {data.cards.length > 0 && (
        <div aria-busy={stale} className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {data.cards.map((c) => (
            <ChartCard key={c.classifier_id + (c.measure ?? "")} card={c} basePath={basePath} />
          ))}
        </div>
      )}
      {data.chips.length > 0 && (
        <ul className="flex flex-wrap gap-2 mt-4">
          {data.chips.map((chip) => (
            <li key={chip.classifier_id} className="rounded-pill bg-surface border border-border px-2.5 py-0.5 text-small text-muted">
              {chipText(chip)}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
