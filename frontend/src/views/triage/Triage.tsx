// SPDX-License-Identifier: Apache-2.0
/*
 * Triage — the front door: an inbox that wants to be empty.
 *
 * ONE ranked list, worst first. No claimed/unclaimed bands and no owner column:
 * nothing in this product is assigned, so ranking is magnitude then recency and
 * the server does it (`ix_eval_case_live_rank`). Muted cases and the week's
 * closures are a filter, not furniture. When nothing is open the serif all-clear
 * state renders instead, with a proof line built from real coverage counts.
 *
 * This reads `GET {base}/cases` — an indexed table read. The CUSUM replay that
 * decides what is degrading runs on a worker, never on this page load.
 */
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import type { Case, Vitals } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, PageHeader, TableSkeleton } from "../../ui";
import { Dot, ListChassis, StateDot, causeLine, detectorLabel, magnitudePair, timeAgo } from "./bits";
import { PipelineEmpty } from "./PipelineEmpty";
import { resolveState } from "./emptyState";
import { useOnboarding } from "../onboarding/useOnboarding";
import { useCapabilities } from "../../capabilities/useCapabilities";

type Lens = "open" | "muted" | "resolved";

export function Triage() {
  const { orgSlug, projectSlug, api } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const navigate = useNavigate();

  const triageQ = useQuery({ queryKey: ["cases", api.base], queryFn: api.getTriage });
  // The pulse strip is deterministic vitals, never judged and never paged.
  const vitalsQ = useQuery({ queryKey: ["vitals", "triage", api.base], queryFn: () => api.getVitals(7) });
  // Passive read (no poll): Triage is not the screen someone stares at while wiring an exporter.
  const onboarding = useOnboarding();
  /*
   * Whether the org holds a model provider key — the fact that separates "triage looked and found
   * nothing" from "triage never ran". Read from `model-settings` rather than the providers list:
   * `configured_providers` is org-scoped data on an ungated project route, while the providers list
   * itself is gated on `byo_provider_keys_enabled` and ERRORS for an org without it, which is
   * exactly the org this screen most needs the answer for.
   *
   * Cached for the session-ish: a credential is not something that changes while someone reads an
   * empty queue, and this read is on the path of every arrival at the front door.
   */
  const modelSettingsQ = useQuery({
    queryKey: ["model-settings", api.base],
    queryFn: api.getModelSettings,
    staleTime: 5 * 60_000,
  });
  const canAddProvider = useCapabilities().isEnabled("byo_provider_keys_enabled");

  const [lens, setLens] = useState<Lens>("open");

  const open = triageQ.data?.cases ?? [];
  const muted = triageQ.data?.muted ?? [];
  const resolved = triageQ.data?.recently_resolved ?? [];
  const shown = lens === "open" ? open : lens === "muted" ? muted : resolved;

  const allClear = triageQ.data != null && open.length === 0;
  const openCase = (id: string) => navigate(`${basePath}/cases/${id}`);

  return (
    <>
      <div className="pt-9 px-10 pb-0">
        <PageHeader
          kicker="Triage"
          title="Cases"
          actions={
            <>
              <Button
                size="sm"
                variant={lens === "muted" ? "secondary" : "ghost"}
                onClick={() => setLens((l) => (l === "muted" ? "open" : "muted"))}
                aria-pressed={lens === "muted"}
              >
                Muted · {muted.length}
              </Button>
              <Button
                size="sm"
                variant={lens === "resolved" ? "secondary" : "ghost"}
                onClick={() => setLens((l) => (l === "resolved" ? "open" : "resolved"))}
                aria-pressed={lens === "resolved"}
              >
                Resolved 7d · {resolved.length}
              </Button>
            </>
          }
        />

        {triageQ.isLoading && <TableSkeleton rows={5} cols={3} />}
        {triageQ.isError && <ErrorNote error={triageQ.error} />}

        {/* One screen for every empty queue. Which of the four it is, and where it sends the reader,
            is `resolveState`'s call — see its header for why an empty queue is four states and not
            one, and why a stopped exporter is a modifier on this screen rather than a fifth. */}
        {triageQ.data && lens === "open" && allClear && (
          <PipelineEmpty
            state={resolveState(triageQ.data.watching, onboarding, basePath, {
              // null until the read settles — see ProviderFacts for why that must not fire the modifier.
              configured: modelSettingsQ.data?.configured_providers.length ?? null,
              canConfigure: canAddProvider,
            })}
          />
        )}

        {triageQ.data && !(lens === "open" && allClear) && (
          <>
            <ListChassis>
              {shown.map((c) => (
                <CaseRow key={c.id} item={c} onOpen={openCase} />
              ))}
              {shown.length === 0 && <EmptyRow lens={lens} />}
            </ListChassis>

            {lens === "open" && (
              <p className="mt-4.5 mx-0 mb-0 text-small text-subtle">
                Resolved 7d{" "}·{" "}{resolved.length}{" "}·{" "}Muted{" "}·{" "}{muted.length}
              </p>
            )}
          </>
        )}
      </div>

      {/* Pulse strip — renders in BOTH states. */}
      <div className="pt-7 px-10 pb-8">
        {vitalsQ.data && <PulseStrip vitals={vitalsQ.data} vitalsTo={`${basePath}/vitals`} />}
      </div>
    </>
  );
}

function EmptyRow({ lens }: { lens: Lens }) {
  const text =
    lens === "muted"
      ? "Nothing is muted."
      : lens === "resolved"
        ? "No cases resolved in the last 7 days."
        : "Nothing needs you.";
  return (
    <div className="bg-surface text-subtle py-3.5 px-4 text-small">
      {text}
    </div>
  );
}

function CaseRow({ item, onOpen }: { item: Case; onOpen: (id: string) => void }) {
  const pair = magnitudePair(item);
  const resolved = item.state === "resolved";
  const cause = causeLine(item);

  return (
    <button
      type="button"
      onClick={() => onOpen(item.id)}
      className="flex items-center w-full text-left bg-surface hover:bg-hover transition-colors cursor-pointer gap-4 py-3.5 px-4">
      {!resolved && <StateDot state={item.state} analysed={item.rca_verdict != null} />}

      <span className="min-w-0 flex-1">
        <span className="block truncate text-fg text-body">
          {item.title}
        </span>
        {/* What the analysis concluded, on its own line rather than appended to the title. Appending
            it produces one unreadable run-on — the title is already a whole sentence and a cause is
            another — and it is the second thing scanned, not part of the first. */}
        {cause && (
          <span className="block truncate text-muted mt-0.75 text-small">
            {cause.hedged && <span className="text-subtle">Likely: </span>}
            {cause.text}
          </span>
        )}
        <span className="flex items-center text-muted gap-2 mt-0.5 text-small">
          <span className="font-mono text-subtle">{item.reference}</span>
          <Dot />
          <span>{detectorLabel(item.detector)}</span>
          {item.call_site_id && (
            <>
              <Dot />
              <span className="font-mono">{item.call_site_id}</span>
            </>
          )}
          <Dot />
          <span>{resolved ? `resolved ${timeAgo(item.resolved_at ?? item.opened_at)}` : timeAgo(item.opened_at)}</span>
        </span>
      </span>

      {/* Magnitude only where the detector actually moved a rate; otherwise the
          row stays quiet rather than printing a number that means something else. */}
      {pair && (
        <span
          className="font-mono text-fg text-body"
          style={{ fontVariantNumeric: "tabular-nums", whiteSpace: "nowrap" }}
        >
          {pair}
        </span>
      )}
    </button>
  );
}

/* ------------------------------------------------------------ pulse strip */

function usd(value: number | null | undefined): string {
  if (value == null) return "—";
  return value >= 100 ? `$${Math.round(value)}` : `$${value.toFixed(2)}`;
}

function pct(value: number | null | undefined): string {
  if (value == null) return "—";
  const sign = value > 0 ? "+" : "";
  return `${sign}${Math.round(value)}%`;
}

function ms(value: number | null | undefined): string {
  if (value == null) return "—";
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`;
}

/**
 * Deterministic vitals with their deltas, one line, never paged. Values come
 * from the same `/vitals` read the Vitals page uses, so the strip and the page
 * cannot disagree.
 */
function PulseStrip({ vitals, vitalsTo }: { vitals: Vitals; vitalsTo: string }) {
  const t = vitals.total;
  const entries = [
    { label: "Spend", value: usd(t.cost.usd), delta: pct(t.cost.delta_pct_per_turn) },
    { label: "p95 latency", value: ms(t.duration.p95_ms), delta: pct(t.duration.delta_pct) },
  ];

  return (
    // Wraps rather than overflows. Every child is `nowrap` — a metric must never break between its
    // label and its number — so with a single non-wrapping row the strip had no way to absorb a
    // narrow main column (a wide sidebar, a small window) except by running "Open Vitals" off the
    // right edge. `flex-wrap` lets the link, and then a metric, drop to a second line instead; the
    // link keeps its `ml-auto` so it stays right-aligned on whichever line it ends up on.
    <div
      className="flex flex-wrap items-center bg-surface gap-x-4.5 gap-y-2 py-3 px-4"
      style={{ border: "1px solid var(--color-border)", borderRadius: "var(--radius-card)" }}
    >
      <span className="font-mono uppercase text-subtle text-label" style={{ whiteSpace: "nowrap" }}>
        Vitals 7d
      </span>
      {entries.map((p, i) => (
        // Not a wrapping container itself: the separator belongs to the metric after it, and a line
        // that began with a bare rule would read as a typo.
        <span key={p.label} className="flex items-baseline gap-4.5">
          {i > 0 && (
            <span aria-hidden="true" style={{ color: "var(--color-border-strong)" }}>
              |
            </span>
          )}
          <span className="flex items-baseline gap-2" style={{ whiteSpace: "nowrap" }}>
            <span className="text-muted text-body">
              {p.label}
            </span>
            <span className="font-mono text-fg text-code" style={{ fontVariantNumeric: "tabular-nums" }}>
              {p.value}
            </span>
            {/* Deterministic delta — never colored here. */}
            <span className="font-mono text-subtle text-label">
              {p.delta}
            </span>
          </span>
        </span>
      ))}
      <Link
        to={vitalsTo}
        className="text-link hover:bg-hover transition-colors py-1 px-2 text-small"
        style={{ marginLeft: "auto", borderRadius: "var(--radius-control)", whiteSpace: "nowrap" }}
      >
        Open Vitals
      </Link>
    </div>
  );
}
