// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import type { PromptChatBody, PromptDetail, PromptVersionView, VersionSummary, VersionUsage } from "../../api/types";
import { Button, ErrorNote, LoadingRow, Table, TableSkeleton, TBody, TD, TH, THead, TR } from "../../ui";
import { formatCost } from "../traces/index-data";
import { chatSummary, ChatColumns, ConfigColumns, configSummary, DiffColumns } from "./DiffView";
import { LabelDialog } from "./LabelEditor";
import { LabelBadge, Panel, VersionSelect } from "./promptBits";
import { diffBody, diffChat, diffConfigKeys } from "./promptDiff";
import { labelsWithoutLatest } from "./promptFolders";
import { usePromptUsage, usePromptVersion } from "./promptReads";
import { errorRate, formatMs } from "./UsagePanel";

/** `versions` is newest first; the selected version on the right, the next older one (or newer, for the oldest) on the left. */
export function defaultPair(versions: VersionSummary[], selected: number): { left: number; right: number } {
  const i = versions.findIndex((v) => v.version === selected);
  const partner = i + 1 < versions.length ? versions[i + 1] : versions[i - 1];
  return { left: partner.version, right: selected };
}

const SIDES = "Removed text is marked on the left, added text on the right.";
const NUM = "whitespace-nowrap text-right font-mono tabular-nums";

function Labels({ labels }: { labels: string[] }) {
  const own = labelsWithoutLatest(labels);
  return (
    <span className="flex flex-wrap gap-1">
      {own.map((l) => (
        <LabelBadge key={l} label={l} text={l} />
      ))}
      {labels.includes("latest") && <LabelBadge label="latest" text="latest" />}
    </span>
  );
}

function Diff({ detail, a, b }: { detail: PromptDetail; a: PromptVersionView; b: PromptVersionView }) {
  const config = diffConfigKeys(a.config, b.config);
  const configNote = configSummary(config);
  const chat = detail.type === "chat" ? diffChat(a.body as PromptChatBody, b.body as PromptChatBody) : null;
  const text = detail.type === "text" ? diffBody("text", a.body, b.body) : null;
  const bodySame = chat ? chat.every((r) => r.kind === "same") : text!.every((o) => o.kind === "same");
  if (bodySame && configNote === null) {
    return <p className="text-small text-muted">{`No differences between version ${a.version} and version ${b.version}.`}</p>;
  }
  return (
    <>
      <Panel title="Prompt" aside={chat ? `${chatSummary(chat)}. ${SIDES}` : SIDES}>
        {chat ? <ChatColumns rows={chat} left={a.version} right={b.version} /> : <DiffColumns ops={text!} />}
      </Panel>
      <Panel title="Config" aside={configNote ?? "No changes"}>
        <ConfigColumns rows={config} />
      </Panel>
    </>
  );
}

function UsageTable({ promptId, rows }: { promptId: string; rows: VersionSummary[] }) {
  const usage = usePromptUsage(promptId, 7);
  if (usage.isPending) return <TableSkeleton rows={2} cols={6} />;
  if (usage.isError) return <ErrorNote error={usage.error} />;
  const byVersion = new Map<string | null, VersionUsage>(usage.data.versions.map((u) => [u.version, u]));
  return (
    <Table>
      <THead>
        <TR>
          <TH>Version</TH>
          {["Calls", "Errors", "p50", "p95", "Cost"].map((h) => (
            <TH key={h} className="text-right">
              {h}
            </TH>
          ))}
        </TR>
      </THead>
      <TBody>
        {rows.map((v) => {
          const u = byVersion.get(String(v.version));
          return (
            <TR key={v.id}>
              <TD>
                <span className="flex items-center gap-2">
                  <span className="font-mono">v{v.version}</span>
                  <Labels labels={v.labels} />
                </span>
              </TD>
              <TD className={NUM}>{(u?.calls ?? 0).toLocaleString("en-US")}</TD>
              <TD className={NUM}>{u ? errorRate(u.error_calls, u.calls) : "—"}</TD>
              <TD className={NUM}>{formatMs(u?.p50_ms ?? null)}</TD>
              <TD className={NUM}>{formatMs(u?.p95_ms ?? null)}</TD>
              <TD className={NUM}>{u ? formatCost(u.cost_usd) : "—"}</TD>
            </TR>
          );
        })}
      </TBody>
    </Table>
  );
}

/** Two versions side by side: the prompt message by message, the config key by key, and their calls this week. */
export function VersionCompare({
  detail,
  versions,
  from,
  to,
  onPick,
  backHref,
  metricsHref,
}: {
  detail: PromptDetail;
  versions: VersionSummary[];
  from: number;
  to: number;
  onPick: (from: number, to: number) => void;
  backHref: string;
  metricsHref: string;
}) {
  const [promoting, setPromoting] = useState(false);
  const l = usePromptVersion(detail.id, from, true);
  const r = usePromptVersion(detail.id, to, true);
  const toLabels = r.data?.labels ?? versions.find((v) => v.version === to)?.labels ?? [];
  const pair = [from, to].map(
    (n) => versions.find((v) => v.version === n) ?? { id: `v-${n}`, version: n, labels: [] as string[] },
  ) as VersionSummary[];

  const content = () => {
    if (from === to) return <p className="text-small text-muted">Pick two different versions.</p>;
    const failed = [l, r].find((q) => q.isError);
    if (failed) return <ErrorNote error={failed.error} />;
    if (!l.data || !r.data) return <LoadingRow label="Loading versions…" />;
    return <Diff detail={detail} a={l.data} b={r.data} />;
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <Link
            to={backHref}
            aria-label="Back to versions"
            className="inline-flex size-8 items-center justify-center rounded-control border border-border text-muted hover:bg-hover hover:text-fg"
          >
            <ArrowLeft size={14} strokeWidth={1.75} aria-hidden="true" />
          </Link>
          <h2 className="text-h2 text-fg">Compare versions</h2>
          <VersionSelect label="From" value={from} versions={versions} onChange={(v) => onPick(v, to)} />
          <VersionSelect label="To" value={to} versions={versions} onChange={(v) => onPick(from, v)} />
        </div>
        {from !== to && !toLabels.includes("production") && (
          <Button size="sm" onClick={() => setPromoting(true)}>
            Promote version {to} to production
          </Button>
        )}
      </div>
      {content()}
      <section className="flex flex-col gap-3">
        <div className="flex items-baseline justify-between gap-3">
          <h3 className="text-h3 text-fg">Usage in the last 7 days</h3>
          <Link to={metricsHref} className="text-small text-link hover:text-link-hover">
            View metrics by version
          </Link>
        </div>
        <UsageTable promptId={detail.id} rows={pair} />
      </section>
      {promoting && (
        <LabelDialog
          promote
          promptId={detail.id}
          version={to}
          labels={toLabels}
          allLabels={detail.labels}
          latestVersion={detail.latest_version}
          onClose={() => setPromoting(false)}
        />
      )}
    </div>
  );
}
