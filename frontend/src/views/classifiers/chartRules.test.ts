// SPDX-License-Identifier: Apache-2.0
/*
 * The rules a chart card draws by, apart from React: the axis ticks, the headline and its delta, which cases get a
 * lane, the hover text, and which call site and tool the page opens on. Every expected value here comes from the
 * product brief or a hand calculation.
 */
import { describe, expect, it } from "vitest";
import type { ChartCard, ChartCaseSpan, ChartChip, ChartDay, ChartToolOption, ClassifierMenuItem } from "../../api/types";
import {
  chipText,
  countBar,
  dayTooltip,
  defaultCallSite,
  defaultTool,
  headlineOf,
  lanesOf,
  laneTooltip,
  menuStatus,
  toolGroups,
  utcDay,
  xTicks,
  yAxis,
} from "./chartRules";

/** `n` dense days ending on `last`, oldest first, as the server sends them. */
function datesEnding(last: string, n: number): string[] {
  const end = Date.parse(`${last}T00:00:00Z`);
  return Array.from({ length: n }, (_, i) => new Date(end - (n - 1 - i) * 86_400_000).toISOString().slice(0, 10));
}

const day = (date: string, d: Partial<ChartDay>): ChartDay => ({
  date,
  checked: null,
  flagged: null,
  n: null,
  p50: null,
  p95: null,
  count: null,
  total: null,
  ...d,
});

function card(c: Partial<ChartCard>): ChartCard {
  return {
    classifier_id: "c1",
    classifier_key: "frustration",
    name: "Frustration",
    kind: "rate",
    measure: null,
    unit: "fraction",
    learning: null,
    headline: { value: null, delta: null },
    baseline: null,
    arming: null,
    days: [],
    cases: { open_cases: 0, spans: [] },
    ...c,
  };
}

const rateBaseline = (rate: number) => ({ calls: 1000, failures: 42, rate, pinned: false, p50: null, p95: null });
const rangeBaseline = (p50: number, p95: number) => ({ calls: null, failures: null, rate: null, pinned: null, p50, p95 });

function span(s: Partial<ChartCaseSpan>): ChartCaseSpan {
  return {
    finding_id: "f",
    case_id: "case",
    case_reference: "C-1",
    case_title: "A case",
    case_state: "open",
    start_at: "2026-10-01T09:00:00Z",
    end_at: null,
    resolution: null,
    disposition: null,
    ...s,
  };
}

describe("yAxis", () => {
  // Bug: ticks at odd steps (1.95%, 3.9%) that a reader cannot hold, or a top tick below the data.
  it("steps by 1, 2 or 5 times a power of ten, from zero to the first step at or above the data", () => {
    expect(yAxis(7.8, "percent").ticks.map((t) => t.label)).toEqual(["0%", "2%", "4%", "6%", "8%"]);
    expect(yAxis(16.5, "seconds").ticks.map((t) => t.label)).toEqual(["0 s", "5 s", "10 s", "15 s", "20 s"]);
    expect(yAxis(0.045, "usd").ticks.map((t) => t.label)).toEqual(["$0", "$0.02", "$0.04", "$0.06"]);
    expect(yAxis(0.9, "percent").ticks.map((t) => t.label)).toEqual(["0%", "0.5%", "1.0%"]);
  });

  // Bug: a count axis with half detections on it ("0.5"), or a flat axis that divides by zero on a quiet range.
  it("counts in whole steps, and still draws an axis when nothing happened", () => {
    expect(yAxis(3, "count").ticks.map((t) => t.label)).toEqual(["0", "1", "2", "3"]);
    expect(yAxis(0, "count").ticks.map((t) => t.label)).toEqual(["0", "1"]);
    expect(yAxis(0, "percent").top).toBeGreaterThan(0);
  });
});

describe("xTicks", () => {
  // Bug: ticks counted forward from the first day, so the last tick misses today.
  it("marks every week back from today on 28 days", () => {
    expect(xTicks(datesEnding("2026-10-08", 28))).toEqual([
      { index: 6, label: "Sep 17" },
      { index: 13, label: "Sep 24" },
      { index: 20, label: "Oct 1" },
      { index: 27, label: "Oct 8" },
    ]);
  });

  it("marks every day on 7 days and every two weeks on 90", () => {
    expect(xTicks(datesEnding("2026-10-08", 7)).map((t) => t.label)).toEqual([
      "Oct 2",
      "Oct 3",
      "Oct 4",
      "Oct 5",
      "Oct 6",
      "Oct 7",
      "Oct 8",
    ]);
    expect(xTicks(datesEnding("2026-10-08", 90))).toEqual([
      { index: 5, label: "Jul 16" },
      { index: 19, label: "Jul 30" },
      { index: 33, label: "Aug 13" },
      { index: 47, label: "Aug 27" },
      { index: 61, label: "Sep 10" },
      { index: 75, label: "Sep 24" },
      { index: 89, label: "Oct 8" },
    ]);
  });
});

describe("utcDay", () => {
  // Bug: dates printed in the browser's zone. East of UTC a case that opened at 23:30 UTC reads as the next day, and
  // west of UTC a bare date reads as the day before, so the labels disagree with the UTC buckets they sit under.
  it("prints the UTC day, whatever the browser's zone", () => {
    expect(utcDay("2026-10-08")).toBe("Oct 8");
    expect(utcDay("2026-10-06T23:30:00Z")).toBe("Oct 6");
    expect(utcDay("2026-10-07T00:30:00Z")).toBe("Oct 7");
  });
});

describe("headlineOf", () => {
  // Bug: the change of a rate printed as a percent change, or coloured the same whichever way it went.
  it("gives a rate's change in percentage points, worse when it rose and better when it fell", () => {
    expect(headlineOf(card({ headline: { value: 0.072, delta: 0.03 } }))).toEqual({
      value: "7.2%",
      delta: { text: "+3.0 pp", tone: "worse" },
      label: "flagged, last 7 days",
    });
    expect(
      headlineOf(card({ classifier_key: "tool_error", name: "Tool Errors", headline: { value: 0.018, delta: -0.013 } })),
    ).toEqual({ value: "1.8%", delta: { text: "−1.3 pp", tone: "better" }, label: "failed, last 7 days" });
  });

  // Bug: "+0.0 pp" in red for a rate that did not move.
  it("says No change when the change rounds to zero", () => {
    expect(headlineOf(card({ headline: { value: 0.02, delta: 0.0004 } })).delta).toEqual({
      text: "No change",
      tone: "same",
    });
  });

  // Bug: a delta against a baseline that does not exist yet.
  it("shows no change while the classifier is learning, nor for a count", () => {
    expect(headlineOf(card({ learning: { learned: 34, needed: 100 }, headline: { value: 0.051, delta: null } })).delta).toBeNull();
    expect(
      headlineOf(card({ classifier_key: "secret_leak", kind: "count", unit: "count", headline: { value: 3, delta: null } })),
    ).toEqual({ value: "3", delta: null, label: "detections, last 7 days" });
  });

  // Bug: milliseconds or raw dollars on the wire printed as they arrive.
  it("gives durations in seconds and cost in dollars, with the change in the same unit", () => {
    expect(
      headlineOf(
        card({ classifier_key: "duration_drift", kind: "range", measure: "turn_duration", unit: "ms", headline: { value: 16_500, delta: 5_000 } }),
      ),
    ).toEqual({ value: "16.5 s", delta: { text: "+5.0 s", tone: "worse" }, label: "slow turns (p95), last 7 days" });
    expect(
      headlineOf(card({ classifier_key: "cost_drift", kind: "range", measure: "cost", unit: "usd", headline: { value: 0.041, delta: -0.004 } })),
    ).toEqual({ value: "$0.041", delta: { text: "−$0.004", tone: "better" }, label: "expensive turns (p95), last 7 days" });
  });

  // Bug: "NaN%" for a week with nothing checked.
  it("shows a dash when the last 7 days hold nothing", () => {
    expect(headlineOf(card({ headline: { value: null, delta: null } })).value).toBe("–");
  });
});

describe("lanesOf", () => {
  // Bug: the strip shows three old fixed cases and hides the one that is still open, or draws more lanes than fit.
  it("keeps at most three cases, open ones first and then the newest, drawn oldest first", () => {
    const closedNew = span({ case_reference: "C-5", start_at: "2026-10-05T00:00:00Z", end_at: "2026-10-06T00:00:00Z", case_state: "resolved" });
    const closedNewer = span({ case_reference: "C-6", start_at: "2026-10-06T00:00:00Z", end_at: "2026-10-07T00:00:00Z", case_state: "resolved" });
    const closedOld = span({ case_reference: "C-1", start_at: "2026-09-12T00:00:00Z", end_at: "2026-09-14T00:00:00Z", case_state: "resolved" });
    const openOld = span({ case_reference: "C-2", start_at: "2026-09-20T00:00:00Z" });
    const openMid = span({ case_reference: "C-3", start_at: "2026-09-25T00:00:00Z" });

    const { lanes, more } = lanesOf([closedNew, closedOld, openOld, closedNewer, openMid]);

    expect(lanes.map((l) => l.case_reference)).toEqual(["C-2", "C-3", "C-6"]);
    expect(more).toBe(2);
  });
});

describe("laneTooltip", () => {
  // Bug: the case's internal id shown instead of its reference, or a fixed case read as still open.
  it("names the case, its title, and when it ran", () => {
    expect(laneTooltip(span({ case_reference: "C-43", case_title: "Turns got slower", start_at: "2026-10-06T09:00:00Z" }))).toEqual([
      "Opens C-43",
      "Turns got slower",
      "Open since Oct 6",
    ]);
    expect(
      laneTooltip(
        span({
          case_reference: "C-31",
          case_title: "Users grew frustrated",
          case_state: "resolved",
          start_at: "2026-08-28T09:00:00Z",
          end_at: "2026-09-05T12:00:00Z",
          resolution: "human",
          disposition: "fixed",
        }),
      ),
    ).toEqual(["Opens C-31", "Users grew frustrated", "Aug 28 to Sep 5, fixed"]);
    expect(
      laneTooltip(span({ case_state: "resolved", start_at: "2026-08-28T09:00:00Z", end_at: "2026-09-05T12:00:00Z", resolution: "human" })).at(-1),
    ).toBe("Aug 28 to Sep 5, closed by hand");
  });
});

describe("dayTooltip", () => {
  // Bug: the day's rate against the wrong reference, or a percent change in place of points.
  it("gives a rate day's share, its counts and its gap to the baseline", () => {
    const c = card({ baseline: rateBaseline(0.042), days: [day("2026-10-08", { checked: 116, flagged: 8 })] });
    expect(dayTooltip(c, 0)).toEqual(["Oct 8", "6.9% flagged", "8 of 116 conversations", "+2.7 pp against baseline"]);
  });

  it("says there is no baseline while learning, and that nothing was checked on an empty day", () => {
    const c = card({
      learning: { learned: 34, needed: 100 },
      days: [day("2026-10-07", { checked: 0, flagged: 0 }), day("2026-10-08", { checked: 40, flagged: 2 })],
    });
    expect(dayTooltip(c, 0)).toEqual(["Oct 7", "No conversations checked"]);
    expect(dayTooltip(c, 1)).toEqual(["Oct 8", "5.0% flagged", "2 of 40 conversations", "No baseline yet"]);
  });

  // Bug: p50 and p95 swapped, or the baseline band read as one number.
  it("gives a range day's slow and typical values and the baseline band", () => {
    const c = card({
      classifier_key: "duration_drift",
      kind: "range",
      measure: "turn_duration",
      unit: "ms",
      baseline: rangeBaseline(4_100, 11_500),
      days: [day("2026-10-01", { n: 240, p50: 6_100, p95: 16_000 })],
    });
    expect(dayTooltip(c, 0)).toEqual(["Oct 1", "Slow (p95) 16.0 s", "Typical (p50) 6.1 s", "Baseline 11.5 s and 4.1 s"]);
  });

  // Bug: "reached" judged on every detection of the call site rather than on what the arming bar counts.
  it("says whether a count day reached the bar that opens a finding", () => {
    const arming = { threshold: 2, window_seconds: 86_400, basis: "event_count", confidence: "high" as const };
    const c = card({
      classifier_key: "secret_leak",
      kind: "count",
      unit: "count",
      arming,
      days: [day("2026-10-06", { count: 2, total: 2 }), day("2026-10-07", { count: 1, total: 3 })],
    });
    expect(dayTooltip(c, 0)).toEqual(["Oct 6", "2 detections", "Reached 2, opened a finding"]);
    expect(dayTooltip(c, 1)).toEqual(["Oct 7", "3 detections", "1 counted toward a finding", "Below 2, no finding"]);
  });
});

describe("countBar", () => {
  // Bug: bars drawn from what the arming bar counts, which is project-wide for a user classifier, so a call site's
  // bar stands taller than the detections its tooltip and headline report.
  it("is as tall as the call site's detections, and bright only when the arming count reached the bar", () => {
    const arming = { threshold: 2, window_seconds: 86_400, basis: "event_count", confidence: "any" as const };
    const c = card({ classifier_key: "refund_promise", kind: "count", unit: "count", arming });
    expect(countBar(c, day("2026-10-07", { count: 1, total: 3 }))).toEqual({ height: 3, reached: false });
    expect(countBar(c, day("2026-10-08", { count: 5, total: 1 }))).toEqual({ height: 1, reached: true });
    expect(countBar(card({ kind: "count", unit: "count" }), day("2026-10-08", { count: 4, total: 4 }))).toEqual({
      height: 4,
      reached: false,
    });
  });
});

describe("defaultCallSite", () => {
  const site = (call_site_id: string, open_cases: number, turns: number) => ({ call_site_id, open_cases, turns, learning: false });

  // Bug: the page opens on the busiest call site while another one has open cases.
  it("opens on the call site with the most open cases, then the most turns", () => {
    expect(defaultCallSite([site("busy", 0, 9_000), site("few", 2, 10), site("more", 2, 50)])).toBe("more");
    expect(defaultCallSite([site("a", 0, 10), site("b", 0, 900)])).toBe("b");
    expect(defaultCallSite([])).toBeNull();
  });
});

describe("tools", () => {
  const tool = (tool_key: string, callers: string[], open_cases: number, calls: number): ChartToolOption => ({
    tool_key,
    label: tool_key.replace(/^tool:/, ""),
    callers,
    open_cases,
    calls,
  });

  // Bug: tools this call site never calls listed first, or one list with no way to tell them apart.
  it("groups the tools this call site calls before the others", () => {
    const groups = toolGroups([tool("tool:other", ["cs-b"], 0, 5), tool("tool:mine", ["cs-a"], 0, 1)], "cs-a");
    expect(groups.map((g) => [g.label, g.tools.map((t) => t.label)])).toEqual([
      ["Called by this call site", ["mine"]],
      ["Other tools", ["other"]],
    ]);
  });

  // Bug: a tool with an open case hidden behind the most-called tool.
  it("opens on a tool with an open case, this call site's first, else on this call site's most-called tool", () => {
    const quietMine = tool("tool:quiet", ["cs-a"], 0, 900);
    const smallMine = tool("tool:small", ["cs-a"], 0, 100);
    const caseElsewhere = tool("tool:elsewhere", ["cs-b"], 1, 5);
    const caseMine = tool("tool:cased", ["cs-a"], 1, 3);
    const busyElsewhere = tool("tool:busy", ["cs-b"], 0, 50_000);

    expect(defaultTool([quietMine, smallMine, caseElsewhere], "cs-a")).toBe("tool:elsewhere");
    expect(defaultTool([quietMine, caseElsewhere, caseMine], "cs-a")).toBe("tool:cased");
    expect(defaultTool([smallMine, quietMine, busyElsewhere], "cs-a")).toBe("tool:quiet");
    expect(defaultTool([busyElsewhere], "cs-a")).toBe("tool:busy");
    expect(defaultTool([], "cs-a")).toBeNull();
  });
});

describe("menuStatus", () => {
  const item = (m: Partial<ClassifierMenuItem>): ClassifierMenuItem => ({
    id: "c",
    classifier_key: "frustration",
    name: "Frustration",
    status: "on",
    waiting_reason: null,
    covers: "call_sites",
    all_call_sites: false,
    call_site_count: 2,
    ...m,
  });

  // Bug: every classifier reads "On" whether it runs on one call site, all of them, or waits on a schema.
  it("says where each classifier runs, or why it does not", () => {
    expect(menuStatus(item({}))).toBe("On, 2 call sites");
    expect(menuStatus(item({ call_site_count: 1 }))).toBe("On, 1 call site");
    expect(menuStatus(item({ all_call_sites: true }))).toBe("On, every call site");
    expect(menuStatus(item({ covers: "tools", call_site_count: 0 }))).toBe("On, every tool");
    expect(menuStatus(item({ covers: "call_sites_and_tools" }))).toBe("On, call sites and tools");
    expect(menuStatus(item({ status: "off" }))).toBe("Off");
    expect(menuStatus(item({ status: "waiting", waiting_reason: "no_schema" }))).toBe("Waiting for a schema");
    expect(menuStatus(item({ status: "waiting", waiting_reason: "no_provider" }))).toBe("Paused, no provider key");
  });
});

describe("chipText", () => {
  const chip = (c: Partial<ChartChip>): ChartChip => ({
    classifier_id: "c",
    classifier_key: "k",
    name: "Groundedness",
    state: "off",
    reason: null,
    since: null,
    ...c,
  });

  // Bug: a classifier with no card disappears without a word, and the reader thinks it is not set up.
  it("says why a classifier has no card", () => {
    expect(chipText(chip({}))).toBe("Groundedness off");
    expect(chipText(chip({ name: "Malformed Output", state: "waiting", reason: "no_schema" }))).toBe(
      "Malformed Output waiting for a schema",
    );
    expect(chipText(chip({ name: "Secret Leak", state: "quiet", since: "2026-09-29" }))).toBe("Secret Leak quiet since Sep 29");
    expect(chipText(chip({ name: "Secret Leak", state: "quiet" }))).toBe("Secret Leak has no data yet");
  });
});
