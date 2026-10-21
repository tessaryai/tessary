// SPDX-License-Identifier: Apache-2.0
import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronDown, ChevronUp } from "lucide-react";
import { cn, ErrorNote, LoadingRow, Spinner } from "../../ui";
import { brief, clock, RUN_POLL_MS } from "./runWords";

/** One step of a triage or RCA run, as the steps endpoints send it. Times are offsets from the run's start. */
export type RunStep = {
  seq: number;
  words: string;
  doing: string;
  started_ms: number;
  ended_ms?: number | null;
  ok?: boolean | null;
};

export type StepsPage = { run_id: string; step_count: number; steps: RunStep[] };

/** Reads a run's steps after a seq; `live` while the run can still add steps. */
export type StepsSource = { load: (after: number) => Promise<StepsPage>; live: boolean };

export type RunState = "queued" | "running" | "stopped";

/** Re-reads from the oldest step still open, so its end arrives; else from the newest step. */
function cursor(page: StepsPage | null): number {
  if (!page) return 0;
  const open = page.steps.find((s) => s.ended_ms == null);
  return open ? open.seq - 1 : (page.steps.at(-1)?.seq ?? 0);
}

function merge(prev: StepsPage, next: StepsPage): StepsPage {
  const bySeq = new Map(prev.steps.map((s) => [s.seq, s]));
  for (const s of next.steps) bySeq.set(s.seq, s);
  return { ...next, steps: [...bySeq.values()].sort((a, b) => a.seq - b.seq) };
}

/**
 * Reads a run's steps only while `open`: once on opening, then every RUN_POLL_MS while the run is live, and once
 * more when it stops being live, so the step it ended on shows its end.
 */
export function useRunSteps(
  source: StepsSource | undefined,
  open: boolean,
): { page: StepsPage | null; error: unknown } {
  const [read, setRead] = useState<{ page: StepsPage | null; error: unknown }>({ page: null, error: null });
  const load = useRef(source?.load);
  const page = useRef<StepsPage | null>(null);
  const live = source?.live ?? false;
  useEffect(() => {
    load.current = source?.load;
  });
  useEffect(() => {
    if (!open || !load.current) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const prev = page.current;
        let next = await load.current!(cursor(prev));
        if (prev && next.run_id !== prev.run_id) next = await load.current!(0);
        if (stopped) return;
        page.current = prev?.run_id === next.run_id ? merge(prev, next) : next;
        setRead({ page: page.current, error: null });
      } catch (error) {
        if (stopped) return;
        setRead((r) => ({ page: r.page, error }));
      }
      if (live) timer = setTimeout(tick, RUN_POLL_MS);
    };
    void tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [open, live]);
  return read;
}

function StepsBody({ read, live }: { read: { page: StepsPage | null; error: unknown }; live: boolean }) {
  if (read.error != null) return <ErrorNote error={read.error} />;
  if (!read.page) return <LoadingRow label="Loading steps…" />;
  if (read.page.steps.length === 0) return <span className="text-small text-subtle">No steps yet.</span>;
  const running = live ? read.page.steps.filter((s) => s.ended_ms == null).at(-1)?.seq : undefined;
  return <RunSteps steps={read.page.steps} stepCount={read.page.step_count} runningSeq={running} />;
}

/**
 * Newest last; the step in progress spins, a step that failed is crossed, one still open beside it waits, one the run
 * stopped inside says it did not finish, and a cut list says so.
 */
export function RunSteps({
  steps,
  stepCount,
  runningSeq,
  cap = 30,
}: {
  steps: RunStep[];
  stepCount: number;
  runningSeq?: number;
  cap?: number;
}) {
  const shown = steps.slice(-cap);
  const earlier = stepCount - shown.length;
  return (
    <>
      {earlier > 0 && (
        <span className="text-small text-subtle">
          {earlier} earlier {earlier === 1 ? "step" : "steps"}
        </span>
      )}
      <ol aria-label="Steps" className="m-0 flex list-none flex-col gap-1.25 p-0">
        {shown.map((step) => {
          const running = step.seq === runningSeq;
          const open = step.ended_ms == null;
          const words = !open ? step.words : runningSeq == null ? `${step.doing} (did not finish)` : `${step.doing}…`;
          return (
            <li
              key={step.seq}
              aria-current={running ? "step" : undefined}
              className={cn("flex items-center gap-2 text-small", running ? "text-fg" : "text-muted")}
            >
              {running ? (
                <Spinner size="sm" className="size-2.75 shrink-0" />
              ) : (
                <span aria-hidden="true" className="w-3 shrink-0 text-subtle">
                  {open ? "·" : step.ok ? "✓" : "✕"}
                </span>
              )}
              <span className="min-w-0">{words}</span>
              {!running && (
                <span className="ml-auto font-mono text-label text-subtle tabular-nums">{clock(step.started_ms)}</span>
              )}
            </li>
          );
        })}
      </ol>
    </>
  );
}

function StateMark({ state, size }: { state: RunState; size: "bar" | "list" }) {
  const label = size === "list" ? `RCA ${state}` : { queued: "Queued", running: "Running", stopped: "Stopped" }[state];
  const dot = size === "bar" ? "size-1.75" : "size-1.5";
  if (state === "running") {
    return (
      <span role="img" aria-label={label} className="inline-flex shrink-0">
        <Spinner size="sm" className={size === "list" ? "size-2.5" : undefined} />
      </span>
    );
  }
  return (
    <span
      role="img"
      aria-label={label}
      className={cn(
        "shrink-0 rounded-pill",
        dot,
        state === "stopped" ? "bg-error" : cn("border-[1.5px]", size === "bar" ? "border-muted" : "border-subtle"),
      )}
    />
  );
}

/**
 * The live state of a triage or RCA run under a page header: a mark, the kind, one line of words, the step and
 * time, a toggle for the steps, the settings page that fixes a stop, and a sliding line while it runs.
 */
export function RunBar({
  kind,
  state,
  line,
  meta,
  fix,
  steps,
}: {
  kind: "Triage" | "RCA";
  state: RunState;
  line: string;
  meta?: string;
  fix?: { label: string; to: string };
  steps?: StepsSource;
}) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const read = useRunSteps(steps, open);
  return (
    <div className="mt-5 flex flex-col overflow-hidden rounded-card border border-border bg-surface">
      <div className="flex min-h-10.5 items-center gap-3 px-3.5 py-2.5">
        <StateMark state={state} size="bar" />
        <span className="whitespace-nowrap font-mono text-label uppercase text-muted">{kind}</span>
        <span title={line} className="min-w-0 flex-1 truncate text-small text-fg-secondary">
          {line}
        </span>
        {meta && <span className="whitespace-nowrap font-mono text-label text-subtle tabular-nums">{meta}</span>}
        {steps && (
          <button
            type="button"
            aria-expanded={open}
            aria-controls={open ? panelId : undefined}
            onClick={() => setOpen((o) => !o)}
            className="inline-flex items-center gap-1 whitespace-nowrap bg-transparent p-0 text-small text-muted transition-colors hover:text-fg"
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {open ? "Hide steps" : "Show steps"}
            {open ? <ChevronUp size={12} aria-hidden="true" /> : <ChevronDown size={12} aria-hidden="true" />}
          </button>
        )}
        {fix && (
          <Link to={fix.to} className="whitespace-nowrap text-small text-link hover:text-link-hover">
            {fix.label}
          </Link>
        )}
      </div>
      {state === "running" && (
        <div role="progressbar" aria-label={`${kind} running`} className="relative h-0.5 overflow-hidden bg-border">
          <span className="absolute inset-y-0 w-3/10 bg-muted animate-run-slide" />
        </div>
      )}
      {open && steps && (
        <div id={panelId} className="flex flex-col gap-1.25 border-t border-border pt-2.5 pr-3.5 pb-3 pl-10">
          <StepsBody read={read} live={steps.live} />
        </div>
      )}
    </div>
  );
}

/** The cases-list mark for a case whose RCA is queued, running or stopped, with how long on hover. */
export function RunMark({ state, since }: { state: RunState; since?: string | null }) {
  const [hover, setHover] = useState(false);
  const tip = state === "stopped" || !since ? `RCA ${state}` : `RCA ${state} · ${brief(Date.now() - Date.parse(since))}`;
  return (
    <span
      className="relative inline-flex shrink-0 items-center"
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <StateMark state={state} size="list" />
      {hover && (
        <span
          role="tooltip"
          className="absolute -right-1.5 bottom-full z-10 mb-1.5 whitespace-nowrap rounded-control border border-border-strong bg-overlay px-2 py-1.25 text-small text-fg"
        >
          {tip}
        </span>
      )}
    </span>
  );
}
