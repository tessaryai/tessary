// SPDX-License-Identifier: Apache-2.0
/*
 * Traces index — the filter bar.
 *
 * Every filter here is server-side. The list is keyset-paginated, so narrowing it
 * in the browser would only ever narrow the page you happen to have loaded; each
 * control writes to the URL, the URL is the query, and the query is re-pulled.
 *
 * The time range is the one control with real subtlety. Its bounds are resolved
 * to ABSOLUTE instants at selection time, not sent as `now-30d`: paging walks a
 * keyset over `created_at`, and a lower bound that drifts forward between page
 * one and page four would silently drop rows out from under the cursor. Picking
 * "Past 30 days" pins an instant; the ↻ button re-pins it.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Calendar, Check, ChevronDown, RotateCw } from "lucide-react";
import { Button, Input, cn, isoToLocal, localToIso } from "../../ui";

// ---- time range ------------------------------------------------------------

export type PresetKey = "30m" | "1h" | "6h" | "1d" | "3d" | "7d" | "14d" | "30d" | "90d";

export const TIME_PRESETS: { key: PresetKey; label: string; ms: number }[] = [
  { key: "30m", label: "Past 30 min", ms: 30 * 60_000 },
  { key: "1h", label: "Past 1 hour", ms: 3_600_000 },
  { key: "6h", label: "Past 6 hours", ms: 6 * 3_600_000 },
  { key: "1d", label: "Past 1 day", ms: 86_400_000 },
  { key: "3d", label: "Past 3 days", ms: 3 * 86_400_000 },
  { key: "7d", label: "Past 7 days", ms: 7 * 86_400_000 },
  { key: "14d", label: "Past 14 days", ms: 14 * 86_400_000 },
  { key: "30d", label: "Past 30 days", ms: 30 * 86_400_000 },
  { key: "90d", label: "Past 90 days", ms: 90 * 86_400_000 },
];

const PRESET_BY_KEY = new Map(TIME_PRESETS.map((p) => [p.key, p]));

export const DEFAULT_RANGE: PresetKey = "30d";

/** What the picker holds: a rolling preset, everything, or two pinned instants. */
export type TraceTimeRange =
  | { kind: "preset"; key: PresetKey }
  | { kind: "all" }
  | { kind: "custom"; from: string | null; to: string | null };

/** The absolute bounds handed to the API. A preset resolves against `now` once, here. */
export function resolveRange(range: TraceTimeRange, now: number): { from: string | null; to: string | null } {
  if (range.kind === "all") return { from: null, to: null };
  if (range.kind === "custom") return { from: range.from, to: range.to };
  const preset = PRESET_BY_KEY.get(range.key)!;
  return { from: new Date(now - preset.ms).toISOString(), to: null };
}

export function rangeLabel(range: TraceTimeRange): string {
  if (range.kind === "all") return "All time";
  if (range.kind === "preset") return PRESET_BY_KEY.get(range.key)!.label;
  const from = range.from ? new Date(range.from).toLocaleString() : "the beginning";
  const to = range.to ? new Date(range.to).toLocaleString() : "now";
  return `${from} → ${to}`;
}

/** The short badge that leads the control, mirroring the label. */
function rangeBadge(range: TraceTimeRange): string {
  if (range.kind === "preset") return range.key;
  return range.kind === "all" ? "∞" : "⌚";
}

// ---- URL state -------------------------------------------------------------

export type FacetKey = "status" | "kind" | "call_site" | "call_site_scope";

/**
 * Which traces the call-site control keeps. `any` is the default and has no param: a trace without a
 * call site is left out until the reader asks for it. One id is `?call_site=`; `all` and `none` are
 * `?call_site_scope=`, a param of its own because a call site id can be any string.
 */
export type CallSiteFilter = "any" | "all" | "none" | { id: string };

/** Every filter the bar owns, read straight off the URL so a link carries the view. */
export type TraceQueryState = {
  range: TraceTimeRange;
  facets: Record<FacetKey, string | null>;
  callSite: CallSiteFilter;
  /** The submitted search, "" for none. */
  q: string;
};

const FACET_KEYS: FacetKey[] = ["status", "kind", "call_site", "call_site_scope"];

function parseCallSite(params: URLSearchParams): CallSiteFilter {
  const id = params.get("call_site");
  if (id) return { id };
  const scope = params.get("call_site_scope");
  return scope === "all" || scope === "none" ? scope : "any";
}

function parseRange(params: URLSearchParams): TraceTimeRange {
  const raw = params.get("range");
  if (raw === "all") return { kind: "all" };
  if (raw === "custom") return { kind: "custom", from: params.get("from"), to: params.get("to") };
  if (raw && PRESET_BY_KEY.has(raw as PresetKey)) return { kind: "preset", key: raw as PresetKey };
  return { kind: "preset", key: DEFAULT_RANGE };
}

/**
 * The filter bar's state, in the URL. Deep links already land here with
 * `?call_site=` (from Vitals) and `?status=` (from Classifiers); those are the
 * same params the controls write, so an arriving link shows its filter as a chip
 * and the control reads as already set.
 */
export function useTraceQueryState(): {
  state: TraceQueryState;
  setRange: (r: TraceTimeRange) => void;
  setFacet: (key: FacetKey, value: string | null) => void;
  setCallSite: (filter: CallSiteFilter) => void;
  setSearch: (q: string) => void;
  clearAll: () => void;
  activeCount: number;
} {
  const [params, setParams] = useSearchParams();

  const state = useMemo<TraceQueryState>(() => {
    const facets = Object.fromEntries(FACET_KEYS.map((k) => [k, params.get(k)])) as Record<
      FacetKey,
      string | null
    >;
    return { range: parseRange(params), facets, callSite: parseCallSite(params), q: params.get("q") ?? "" };
  }, [params]);

  // Mutations preserve params this bar does not own (?trace=, ?view=, ?span=).
  const write = useCallback(
    (mutate: (next: URLSearchParams) => void) => {
      const next = new URLSearchParams(params);
      mutate(next);
      setParams(next, { replace: true });
    },
    [params, setParams],
  );

  const setRange = useCallback(
    (r: TraceTimeRange) =>
      write((next) => {
        next.delete("from");
        next.delete("to");
        if (r.kind === "preset") next.set("range", r.key);
        else if (r.kind === "all") next.set("range", "all");
        else {
          next.set("range", "custom");
          if (r.from) next.set("from", r.from);
          if (r.to) next.set("to", r.to);
        }
      }),
    [write],
  );

  const setFacet = useCallback(
    (key: FacetKey, value: string | null) =>
      write((next) => {
        if (value) next.set(key, value);
        else next.delete(key);
      }),
    [write],
  );

  const setCallSite = useCallback(
    (filter: CallSiteFilter) =>
      write((next) => {
        next.delete("call_site");
        next.delete("call_site_scope");
        if (typeof filter === "object") next.set("call_site", filter.id);
        else if (filter !== "any") next.set("call_site_scope", filter);
      }),
    [write],
  );

  const setSearch = useCallback(
    (q: string) =>
      write((next) => {
        if (q) next.set("q", q);
        else next.delete("q");
      }),
    [write],
  );

  const clearAll = useCallback(
    () =>
      write((next) => {
        FACET_KEYS.forEach((k) => next.delete(k));
        next.delete("q");
        next.delete("range");
        next.delete("from");
        next.delete("to");
      }),
    [write],
  );

  const activeCount =
    FACET_KEYS.filter((k) => state.facets[k]).length + (params.get("range") ? 1 : 0);

  return { state, setRange, setFacet, setCallSite, setSearch, clearAll, activeCount };
}

// ---- kept for the tab ------------------------------------------------------

/** The params that are the reader's view of the list, as opposed to what is open over it (?trace=). */
const KEPT_PARAMS = [...FACET_KEYS, "q", "range", "from", "to", "groupBy"];

function keptOf(params: URLSearchParams): URLSearchParams {
  const kept = new URLSearchParams();
  for (const k of KEPT_PARAMS) {
    const v = params.get(k);
    if (v != null) kept.set(k, v);
  }
  return kept;
}

/**
 * Keeps the filters for the tab, so leaving the page and coming back does not reset them.
 *
 * <p>The URL stays the source of truth; this only copies its filter params to `sessionStorage` and
 * puts them back when the page opens on a bare URL, the sidebar's plain `/traces`. A URL that brings
 * its own filters, a Vitals or Classifiers deep link, wins and becomes the kept view. Per tab rather
 * than per browser: a new tab starts clean, a reload does not.
 *
 * <p>Returns false until the restored filters are in the URL, so the page never fetches the
 * unfiltered list only to throw it away. That is a wait on the URL itself, not on the navigate call:
 * the router applies the change as a transition, a render or more after it is asked for.
 */
export function useKeptTraceView(scope: string): boolean {
  const [params, setParams] = useSearchParams();
  const key = `tessary:traces:view:${scope}`;
  const [pending, setPending] = useState<URLSearchParams | null>(() => {
    if (keptOf(params).toString() !== "") return null;
    try {
      const saved = keptOf(new URLSearchParams(sessionStorage.getItem(key) ?? ""));
      return saved.toString() !== "" ? saved : null;
    } catch {
      return null;
    }
  });
  const ready = pending == null || keptOf(params).toString() !== "";

  // Once, on arrival: every later change to the URL is the reader's own. A plain effect, since the
  // router drops a navigate made from a layout effect on first mount.
  useEffect(() => {
    if (!pending) return;
    const next = new URLSearchParams(params);
    pending.forEach((v, k) => next.set(k, v));
    setParams(next, { replace: true });
  }, []);

  useEffect(() => {
    if (!ready) return;
    // Settled; from here a URL with no filters is one the reader cleared, not one still arriving.
    if (pending) setPending(null);
    try {
      sessionStorage.setItem(key, keptOf(params).toString());
    } catch {
      // Storage unavailable — the view just isn't kept.
    }
  }, [key, params, pending, ready]);

  return ready;
}

// ---- auto refresh ----------------------------------------------------------

const AUTO_STORAGE_KEY = "tessary:traces:auto-refresh";
const AUTO_INTERVAL_MS = 30_000;

/** How close to the top counts as "still watching the live edge". */
const AT_TOP_PX = 120;

/**
 * A 30-second auto-refresh that yields to the reader.
 *
 * <p>A refresh here is a full re-pin: it resolves a rolling range against a new
 * `now` and drops every page already fetched, because appending to a stale
 * cursor chain would interleave two different windows. That is correct at the
 * top of the list and hostile anywhere else — scroll to the four-hundredth row,
 * and thirty seconds later you are back at the first with your place gone.
 *
 * So a tick only fires while the reader is at the top. Scroll down and it holds
 * (the toggle shows amber and says so); scroll back and it resumes on the next
 * tick. This is the standard live-tail behaviour, and it is why the interval is
 * not merely a react-query `refetchInterval`: that would refetch every loaded
 * page on every tick — seven requests per 30s for a list scrolled to seven pages
 * — and still yank the ground out from under whoever was reading.
 */
export function useAutoRefresh(onTick: () => void): {
  auto: boolean;
  setAuto: (on: boolean) => void;
  paused: boolean;
} {
  const [auto, setAutoState] = useState(() => {
    try {
      return localStorage.getItem(AUTO_STORAGE_KEY) === "on";
    } catch {
      return false;
    }
  });
  const [paused, setPaused] = useState(false);

  const setAuto = useCallback((on: boolean) => {
    setAutoState(on);
    if (!on) setPaused(false);
    try {
      localStorage.setItem(AUTO_STORAGE_KEY, on ? "on" : "off");
    } catch {
      // Storage unavailable — the choice just doesn't persist.
    }
  }, []);

  // Held in a ref so a new handler identity each render does not restart the
  // interval — otherwise the timer resets before it ever reaches 30s.
  const tick = useRef(onTick);
  tick.current = onTick;

  useEffect(() => {
    if (!auto) {
      setPaused(false);
      return;
    }
    const scroller = document.querySelector("main");
    const atTop = () => !scroller || scroller.scrollTop <= AT_TOP_PX;

    const id = setInterval(() => {
      setPaused(!atTop());
      // A backgrounded tab should not be re-pulling the list every 30s.
      if (atTop() && document.visibilityState === "visible") tick.current();
    }, AUTO_INTERVAL_MS);

    // The label also follows the scroll directly. Left to the interval alone it
    // lags by up to 30s, so the control still reads "held" for half a minute
    // after you have scrolled back to the top — stale in the one moment someone
    // is most likely to be looking at it.
    const onScroll = () => setPaused(!atTop());
    scroller?.addEventListener("scroll", onScroll, { passive: true });
    setPaused(!atTop());

    return () => {
      clearInterval(id);
      scroller?.removeEventListener("scroll", onScroll);
    };
  }, [auto]);

  return { auto, setAuto, paused };
}

// ---- dropdown shell --------------------------------------------------------

/** Click-outside + Esc, the one behaviour every control on this bar shares. */
export function useDismissOnOutside(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close();
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, close]);
  return ref;
}

function Panel({
  children,
  label,
  width,
  align = "left",
}: {
  children: React.ReactNode;
  label: string;
  width?: number;
  /** `right` for controls at the page edge, whose panel would otherwise overflow it. */
  align?: "left" | "right";
}) {
  return (
    <div
      role="group"
      aria-label={label}
      className={cn(
        "absolute top-full z-30 mt-1.5 rounded-card border border-border bg-raised p-1.5 shadow-lg",
        align === "right" ? "right-0" : "left-0",
      )}
      style={{ minWidth: width ?? 200 }}
    >
      {children}
    </div>
  );
}

/** One row inside a dropdown panel: a leading mono badge, a label, a selected tick. */
function Row({
  badge,
  label,
  selected,
  onClick,
}: {
  badge?: React.ReactNode;
  label: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onClick}
      className={cn(
        "flex w-full items-center rounded-control px-2 py-1.5 text-small transition-colors hover:bg-hover gap-2.5",
        selected ? "text-fg" : "text-muted",
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      <span
        aria-hidden="true"
        className={cn(
          "inline-flex shrink-0 items-center justify-center rounded-control font-mono py-0.25 px-1 text-label",
          selected ? "bg-selected text-fg" : "text-subtle",
        )}
        style={{ minWidth: 30 }}
      >
        {badge}
      </span>
      <span className="truncate">{label}</span>
    </button>
  );
}

// ---- time range control ----------------------------------------------------

export function TimeRangeControl({
  range,
  onChange,
}: {
  range: TraceTimeRange;
  onChange: (r: TraceTimeRange) => void;
}) {
  const [open, setOpen] = useState(false);
  const [calendar, setCalendar] = useState(range.kind === "custom");
  const ref = useDismissOnOutside(
    open,
    useCallback(() => setOpen(false), []),
  );

  const custom = range.kind === "custom" ? range : { from: null, to: null };
  const [from, setFrom] = useState(isoToLocal(custom.from));
  const [to, setTo] = useState(isoToLocal(custom.to));

  const pick = (r: TraceTimeRange) => {
    onChange(r);
    setOpen(false);
    setCalendar(false);
  };

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-expanded={open}
        aria-haspopup="true"
        onClick={() => setOpen((o) => !o)}
        title={`Time range: ${rangeLabel(range)}`}
        className="inline-flex h-8 items-center rounded-control border border-border bg-surface text-fg transition-colors hover:border-border-strong gap-2 pt-0 pr-2 pb-0 pl-1.5"
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        <span
          aria-hidden="true"
          className="inline-flex items-center justify-center rounded-control bg-selected font-mono text-muted py-0.5 px-1.25 text-label">
          {rangeBadge(range)}
        </span>
        <span className="truncate text-small" style={{ maxWidth: 200 }}>
          {rangeLabel(range)}
        </span>
        <ChevronDown size={13} strokeWidth={1.5} aria-hidden="true" className="text-subtle" />
      </button>

      {open && (
        <Panel label="Time range" width={220}>
          {TIME_PRESETS.map((p) => (
            <Row
              key={p.key}
              badge={p.key}
              label={p.label}
              selected={range.kind === "preset" && range.key === p.key}
              onClick={() => pick({ kind: "preset", key: p.key })}
            />
          ))}
          <Row badge="∞" label="All time" selected={range.kind === "all"} onClick={() => pick({ kind: "all" })} />
          <Row
            badge={<Calendar size={12} strokeWidth={1.5} />}
            label="Select from calendar"
            selected={range.kind === "custom"}
            onClick={() => setCalendar((c) => !c)}
          />
          {calendar && (
            <div className="border-t border-border pt-2.5 px-1.5 pb-1 mt-1">
              <label className="block mb-2">
                <span className="text-label uppercase text-subtle">From</span>
                <Input
                  type="datetime-local"
                  value={from}
                  onChange={(e) => setFrom(e.target.value)}
                  className="font-mono mt-1"
                  
                />
              </label>
              <label className="block mb-2.5">
                <span className="text-label uppercase text-subtle">
                  To <span className="normal-case tracking-normal">(blank = now)</span>
                </span>
                <Input
                  type="datetime-local"
                  value={to}
                  onChange={(e) => setTo(e.target.value)}
                  className="font-mono mt-1"
                  
                />
              </label>
              <Button
                size="sm"
                variant="primary"
                className="w-full"
                disabled={!from && !to}
                onClick={() => pick({ kind: "custom", from: localToIso(from), to: localToIso(to) })}
              >
                Apply
              </Button>
            </div>
          )}
        </Panel>
      )}
    </div>
  );
}

// ---- refresh control -------------------------------------------------------

/**
 * Refreshing, as ONE control: the icon refreshes now, the chevron beside it
 * governs whether that also happens on its own every 30 seconds.
 *
 * Two sibling buttons — a ↻ and an `Auto` toggle — read as two unrelated
 * things sitting next to each other, when they are one capability with a manual
 * and an automatic mode. A split button is the shape that says so.
 *
 * On-ness is carried by the icon breathing and by the interval appearing beside
 * it, not by an accent fill. A filled green control in the header competes with
 * the page's actual accent (links, the primary action) for a setting that is
 * ambient rather than important; a slow pulse reads as "live" without claiming
 * that much attention. It stops when a tick is skipped, so a held refresh looks
 * held rather than silently broken.
 */
export function RefreshControl({
  onRefresh,
  auto,
  onAutoChange,
  paused,
}: {
  onRefresh: () => void;
  auto: boolean;
  onAutoChange: (on: boolean) => void;
  /** Auto is on but ticks are being skipped — the reader has scrolled down. */
  paused: boolean;
}) {
  const [open, setOpen] = useState(false);
  const ref = useDismissOnOutside(
    open,
    useCallback(() => setOpen(false), []),
  );

  const live = auto && !paused;
  const state = !auto
    ? "Refresh now · auto-refresh is off"
    : paused
      ? "Refresh now · auto-refresh held while you are scrolled down"
      : "Refresh now · auto-refreshing every 30s";

  return (
    <div ref={ref} className="relative">
      <div className="inline-flex h-8 items-stretch overflow-hidden rounded-control border border-border bg-surface">
        <button
          type="button"
          onClick={onRefresh}
          title={state}
          aria-label="Refresh traces"
          className="inline-flex items-center text-muted transition-colors hover:bg-hover hover:text-fg gap-1.25 py-0 px-2"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          <RotateCw
            size={13}
            strokeWidth={1.5}
            aria-hidden="true"
            className={cn("shrink-0", live && "animate-pulse")}
            // Slower than the default pulse, which at 1s reads as a warning
            // rather than a heartbeat.
            style={live ? { animationDuration: "2400ms" } : undefined}
          />
          {auto && (
            <span className={cn("font-mono text-label", paused ? "text-subtle" : "text-muted")} >
              30s
            </span>
          )}
        </button>
        <span aria-hidden="true" className="w-px shrink-0 bg-border" />
        <button
          type="button"
          aria-expanded={open}
          aria-haspopup="true"
          aria-label="Refresh options"
          onClick={() => setOpen((o) => !o)}
          className="inline-flex items-center text-subtle transition-colors hover:bg-hover hover:text-fg py-0 px-1.25"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          <ChevronDown size={12} strokeWidth={1.5} aria-hidden="true" />
        </button>
      </div>

      {open && (
        <Panel label="Refresh" width={212} align="right">
          <Row
            badge={auto ? <Check size={11} strokeWidth={2} /> : undefined}
            label="Auto-refresh every 30s"
            selected={auto}
            onClick={() => {
              onAutoChange(!auto);
              setOpen(false);
            }}
          />
          {auto && (
            <p className="text-subtle pt-0.5 px-2.5 pb-1.5 text-label">
              {paused ? "Held while you are scrolled down." : "Pauses while you are scrolled down the list."}
            </p>
          )}
        </Panel>
      )}
    </div>
  );
}

// ---- facet control ---------------------------------------------------------

export type FacetOption = { value: string; label: string };

/**
 * A row above or below the values that is not one of them, such as `Any`. `short` is what the button
 * shows while the row is selected; without it the button reads as unset.
 */
export type FacetFixedRow = { label: string; short?: string; selected: boolean; onSelect: () => void };

/**
 * A single-select filter: `Any` (or the given head rows), whatever values this project actually has,
 * then the foot rows.
 */
export function FacetControl({
  label,
  value,
  options,
  onChange,
  head,
  foot = [],
  emptyHint,
}: {
  label: string;
  value: string | null;
  options: FacetOption[];
  onChange: (v: string | null) => void;
  head?: FacetFixedRow[];
  foot?: FacetFixedRow[];
  emptyHint?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useDismissOnOutside(
    open,
    useCallback(() => setOpen(false), []),
  );
  const heads = head ?? [{ label: "Any", selected: !value, onSelect: () => onChange(null) }];
  const shown = value
    ? (options.find((o) => o.value === value)?.label ?? value)
    : [...heads, ...foot].find((h) => h.selected)?.short;
  const fixedRow = (h: FacetFixedRow) => (
    <Row
      key={h.label}
      label={h.label}
      selected={h.selected}
      onClick={() => {
        h.onSelect();
        setOpen(false);
      }}
    />
  );

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-expanded={open}
        aria-haspopup="true"
        onClick={() => setOpen((o) => !o)}
        className={cn(
          "inline-flex h-8 items-center rounded-control border bg-surface transition-colors hover:border-border-strong gap-1.5 py-0 px-2",
          shown ? "border-border-strong text-fg" : "border-border text-muted",
        )}
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        <span className="text-small">
          {label}
          {shown && (
            <>
              <span className="text-subtle">: </span>
              <span className="font-mono">{shown}</span>
            </>
          )}
        </span>
        <ChevronDown size={13} strokeWidth={1.5} aria-hidden="true" className="text-subtle" />
      </button>

      {open && (
        <Panel label={label}>
          {heads.map(fixedRow)}
          {options.length === 0 ? (
            <p className="text-subtle py-2 px-2.5 text-label">
              {emptyHint ?? "Nothing to filter by yet."}
            </p>
          ) : (
            options.map((o) => (
              <Row
                key={o.value}
                label={o.label}
                selected={o.value === value}
                onClick={() => {
                  onChange(o.value);
                  setOpen(false);
                }}
              />
            ))
          )}
          {foot.map(fixedRow)}
        </Panel>
      )}
    </div>
  );
}

export const STATUS_OPTIONS: FacetOption[] = [
  { value: "ok", label: "ok" },
  { value: "error", label: "error" },
];

