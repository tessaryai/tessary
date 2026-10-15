// SPDX-License-Identifier: Apache-2.0
// Cases is one ranked list, worst first (`ix_eval_case_live_rank`); Findings below holds what has not
// become a case. Until either is open, the setup pipeline takes the Cases section.
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import type { Case, Vitals } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, ErrorNote, PageHeader, Section, TableSkeleton } from "../../ui";
import { Dot, ListChassis, StateDot, causeLine, detectorLabel, timeAgo } from "./bits";
import { usd } from "../../lib/usd";
import { PipelineEmpty } from "./PipelineEmpty";
import { needsSetupScreen, resolveState } from "./emptyState";
import { OpenFindings } from "./OpenFindings";
import { useOnboarding } from "../onboarding/useOnboarding";
import { useCapabilities } from "../../capabilities/useCapabilities";

type Lens = "open" | "resolved";

export function Triage() {
  const { orgSlug, projectSlug, api } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const navigate = useNavigate();

  const triageQ = useQuery({ queryKey: ["cases", api.base], queryFn: api.getTriage });
  // The pulse strip is deterministic vitals, never judged and never paged.
  const vitalsQ = useQuery({ queryKey: ["vitals", "triage", api.base], queryFn: () => api.getVitals(7) });
  const onboarding = useOnboarding();
  /*
   * Whether the org holds a provider key, from `model-settings`: the providers list is gated on
   * `byo_provider_keys_enabled` and errors for exactly the org this screen most needs the answer for.
   */
  const modelSettingsQ = useQuery({
    queryKey: ["model-settings", api.base],
    queryFn: api.getModelSettings,
    staleTime: 5 * 60_000,
  });
  const canAddProvider = useCapabilities().isEnabled("byo_provider_keys_enabled");

  const [lens, setLens] = useState<Lens>("open");

  const open = triageQ.data?.cases ?? [];
  const resolved = triageQ.data?.recently_resolved ?? [];
  const shown = lens === "open" ? open : resolved;

  const openCase = (id: string) => navigate(`${basePath}/cases/${id}`);
  const setup =
    triageQ.data && lens === "open" && open.length === 0
      ? resolveState(triageQ.data.watching, onboarding, basePath, {
          // null until the read settles — see ProviderFacts for why that must not fire the modifier.
          configured: modelSettingsQ.data?.configured_providers.length ?? null,
          canConfigure: canAddProvider,
        })
      : null;
  const showSetup = setup != null && needsSetupScreen(setup);
  // Findings waits for something to list: a setup screen over a queue with no open finding stands alone.
  const showFindings =
    triageQ.isError || (triageQ.data != null && !(showSetup && (triageQ.data.watching.open_findings ?? 0) === 0));

  return (
    <>
      <div className="pt-9 px-10 pb-0">
        <PageHeader title="Triage" />

        <Section
          title="Cases"
          actions={
            <Button
              size="sm"
              variant={lens === "resolved" ? "secondary" : "ghost"}
              onClick={() => setLens((l) => (l === "resolved" ? "open" : "resolved"))}
              aria-pressed={lens === "resolved"}
            >
              Closed 7d · {resolved.length}
            </Button>
          }
        >
          {triageQ.isLoading && <TableSkeleton rows={5} cols={3} />}
          {triageQ.isError && <ErrorNote error={triageQ.error} />}

          {showSetup && <PipelineEmpty state={setup} />}

          {triageQ.data && !showSetup && (
            <>
              <ListChassis>
                {shown.map((c) => (
                  <CaseRow key={c.id} item={c} onOpen={openCase} />
                ))}
                {shown.length === 0 && <EmptyRow lens={lens} />}
              </ListChassis>

              {lens === "open" && (
                <p className="mt-4.5 mx-0 mb-0 text-small text-subtle">
                  Closed 7d{" "}·{" "}{resolved.length}
                </p>
              )}
            </>
          )}
        </Section>

        {showFindings && <OpenFindings basePath={basePath} />}
      </div>

      {/* Pulse strip — renders in BOTH states. */}
      <div className="pt-7 px-10 pb-8">
        {vitalsQ.data && <PulseStrip vitals={vitalsQ.data} vitalsTo={`${basePath}/vitals`} />}
      </div>
    </>
  );
}

function EmptyRow({ lens }: { lens: Lens }) {
  const text = lens === "resolved" ? "No cases closed in the last 7 days." : "Nothing needs you.";
  return (
    <div className="bg-surface text-subtle py-3.5 px-4 text-small">
      {text}
    </div>
  );
}

function CaseRow({ item, onOpen }: { item: Case; onOpen: (id: string) => void }) {
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
        {/* The analysis conclusion goes on its own line; appended to the title it makes one run-on sentence. */}
        {cause && (
          <span className="block truncate text-muted mt-0.75 text-small">
            {cause}
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
          <span>{resolved ? `closed ${timeAgo(item.resolved_at ?? item.opened_at)}` : timeAgo(item.opened_at)}</span>
        </span>
      </span>
    </button>
  );
}

/* ------------------------------------------------------------ pulse strip */

function pct(value: number | null | undefined): string {
  if (value == null) return "—";
  const sign = value > 0 ? "+" : "";
  return `${sign}${Math.round(value)}%`;
}

function ms(value: number | null | undefined): string {
  if (value == null) return "—";
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`;
}

/** Deterministic vitals with their deltas, from the same `/vitals` read as the Vitals page. */
function PulseStrip({ vitals, vitalsTo }: { vitals: Vitals; vitalsTo: string }) {
  const t = vitals.total;
  const entries = [
    { label: "Spend", value: usd(t.cost.usd), delta: pct(t.cost.delta_pct_per_turn) },
    { label: "p95 latency", value: ms(t.duration.p95_ms), delta: pct(t.duration.delta_pct) },
  ];

  return (
    // Every child is `nowrap`, so `flex-wrap` is what lets a narrow column move the link, then a metric, to a
    // second line instead of overflowing.
    <div
      className="flex flex-wrap items-center bg-surface gap-x-4.5 gap-y-2 py-3 px-4"
      style={{ border: "1px solid var(--color-border)", borderRadius: "var(--radius-card)" }}
    >
      <span className="font-mono uppercase text-subtle text-label" style={{ whiteSpace: "nowrap" }}>
        Vitals 7d
      </span>
      {entries.map((p, i) => (
        // Not wrapping itself: the separator belongs to the metric after it.
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
