// SPDX-License-Identifier: Apache-2.0
/*
 * One chart card as the reader meets it: the pills, the headline, the steps and bars on the time axis, the baseline or
 * arming line, the cases strip and the hover text. The rules behind the numbers are tested in chartRules.test.ts; these tests catch the card that
 * draws the wrong thing from right numbers.
 */
import { fireEvent, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ChartCard as Card, ChartCaseSpan, ChartPoint } from "../../api/types";
import { currentLocation, renderRoute } from "../../test/render";
import { ChartCard } from "./ChartCard";

const HOUR = 3_600_000;
/** The clock every test reads: 14:20 UTC on the range's last day. */
const NOW = Date.parse("2026-10-08T14:20:00Z");
const RANGE = { days: 28, from_day: "2026-09-11", to_day: "2026-10-08" };

const iso = (ms: number) => new Date(ms).toISOString().replace(".000", "");

function point(start: number, end: number, d: Partial<ChartPoint>): ChartPoint {
  return {
    start_at: iso(start),
    end_at: iso(end),
    open: false,
    checked: null,
    flagged: null,
    n: null,
    p50: null,
    p95: null,
    count: null,
    total: null,
    reached: null,
    ...d,
  };
}

/** One point a day over the range, the last one still filling up to 14:00 today. */
const ratePoints = (): ChartPoint[] =>
  Array.from({ length: 28 }, (_, i) => {
    const start = Date.parse("2026-09-11T00:00:00Z") + i * 24 * HOUR;
    const last = i === 27;
    return point(start, last ? start + 14 * HOUR : start + 24 * HOUR, { checked: 100, flagged: last ? 8 : 4, open: last });
  });

function card(c: Partial<Card>): Card {
  return {
    classifier_id: "c1",
    classifier_key: "frustration",
    name: "Frustration",
    kind: "rate",
    measure: null,
    unit: "fraction",
    learning: null,
    headline: { value: 0.072, delta: 0.03 },
    baseline: { calls: 1000, failures: 42, rate: 0.042, pinned: false, p50: null, p95: null },
    arming: null,
    points: ratePoints(),
    cases: { open_cases: 0, spans: [] },
    ...c,
  };
}

function span(s: Partial<ChartCaseSpan>): ChartCaseSpan {
  return {
    finding_id: "f",
    case_id: "case-1",
    case_reference: "C-43",
    case_title: "Users on support_agent.reply grew frustrated",
    case_state: "open",
    start_at: "2026-10-04T09:00:00Z",
    end_at: null,
    resolution: null,
    disposition: null,
    ...s,
  };
}

const renderCard = (c: Card, range = RANGE) =>
  renderRoute(<ChartCard card={c} range={range} basePath="/orgs/acme/projects/default" />, {
    route: "/orgs/acme/projects/default/classifiers",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "classifiers",
  });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

/** The SVG paths of the card's series, solid and dashed, by their `d`. */
function seriesPaths(region: HTMLElement): { solid: string[]; dashed: string[] } {
  const paths = Array.from(region.querySelectorAll("svg path"));
  return {
    solid: paths.filter((p) => !p.getAttribute("stroke-dasharray")).map((p) => p.getAttribute("d") ?? ""),
    dashed: paths.filter((p) => p.getAttribute("stroke-dasharray")).map((p) => p.getAttribute("d") ?? ""),
  };
}

describe("ChartCard", () => {
  // Bug: open cases drawn in red, which the design keeps for the delta alone, or no word of how far learning got.
  it("shows the open cases and the learning progress as grey pills", () => {
    renderCard(
      card({
        learning: { learned: 340, needed: 1_000 },
        headline: { value: 0.051, delta: null },
        baseline: null,
        cases: { open_cases: 2, spans: [span({}), span({ case_id: "case-2", case_reference: "C-44" })] },
      }),
    );

    const region = screen.getByRole("region", { name: "Frustration" });
    expect(within(region).getByText("2 open cases").className).not.toMatch(/error/);
    expect(within(region).getByText("Learning")).toBeTruthy();
    expect(within(region).getByText("340 / 1,000")).toBeTruthy();
    expect(within(region).queryByText(/Baseline/)).toBeNull();
  });

  // Bug: the delta grey when the rate got worse, or a second red element on the card.
  it("colours only the delta: red when worse, green when better, grey when unchanged", () => {
    const { unmount } = renderCard(card({}));
    expect(screen.getByText("+3.0 pp").className).toMatch(/text-error/);
    expect(screen.getByText("7.2%").className).not.toMatch(/text-error/);
    unmount();

    renderCard(card({ headline: { value: 0.02, delta: -0.013 } }));
    expect(screen.getByText("−1.3 pp").className).toMatch(/text-success/);
  });

  // Bug: a rate card with no baseline line, so the reader cannot see what "normal" was.
  it("labels the dashed baseline of a rate card", () => {
    renderCard(card({}));
    expect(screen.getByText("Baseline 4.2%")).toBeTruthy();
    expect(screen.getByText("% of conversations flagged")).toBeTruthy();
    expect(screen.getByText("UTC")).toBeTruthy();
  });

  // Bug: a daily "opens a finding" line drawn over hourly bars, where no one bar can show whether its day reached it.
  it("draws the arming line only when a bucket is as wide as the arming window", () => {
    const from = Date.parse("2026-10-02T00:00:00Z");
    const hourly = Array.from({ length: 159 }, (_, i) =>
      point(from + i * HOUR, from + (i + 1) * HOUR, { count: 1, total: 1, reached: false, open: i === 158 }),
    );
    const secret = (window_seconds: number) =>
      card({
        classifier_key: "secret_leak",
        name: "Secret Leak",
        kind: "count",
        unit: "count",
        baseline: null,
        headline: { value: 7, delta: null },
        arming: { threshold: 2, window_seconds, basis: "event_count", confidence: "high" },
        points: hourly,
      });
    const week = { days: 7, from_day: "2026-10-02", to_day: "2026-10-08" };

    const { unmount } = renderCard(secret(3_600), week);
    expect(screen.getByText("2 in an hour opens a finding")).toBeTruthy();
    unmount();

    renderCard(secret(86_400), week);
    expect(screen.getByText("Detections per hour")).toBeTruthy();
    expect(screen.queryByText(/opens a finding/)).toBeNull();
  });

  // Bug: every bar grey, so the reader cannot see which windows reached the bar once the threshold line is hidden.
  it("draws a bar over its whole bucket, bright when its arming window reached the bar", () => {
    const from = Date.parse("2026-10-02T00:00:00Z");
    const c = card({
      classifier_key: "secret_leak",
      name: "Secret Leak",
      kind: "count",
      unit: "count",
      baseline: null,
      headline: { value: 3, delta: null },
      arming: { threshold: 2, window_seconds: 86_400, basis: "event_count", confidence: "high" },
      points: [
        point(from, from + 6 * HOUR, { count: 2, total: 2, reached: true }),
        point(from + 6 * HOUR, from + 12 * HOUR, { count: 1, total: 1, reached: false }),
      ],
    });
    renderCard(c, { days: 7, from_day: "2026-10-02", to_day: "2026-10-08" });

    const bars = Array.from(screen.getByRole("region", { name: "Secret Leak" }).querySelectorAll("svg rect[rx]"));
    expect(bars.map((b) => b.getAttribute("fill"))).toEqual(["var(--color-fg)", "var(--color-subtle)"]);
    expect(Number(bars[1].getAttribute("x"))).toBeCloseTo(Number(bars[0].getAttribute("x")) + Number(bars[0].getAttribute("width")) + 1, 5);
  });

  // Bug: a busy call site's steps run together across a quiet night, drawing a rate the empty hours never had.
  it("joins steps only when the next starts within an hour of the last, and dashes the point still filling", () => {
    const from = Date.parse("2026-10-08T00:00:00Z");
    renderCard(
      card({
        points: [
          point(from, from + 2 * HOUR, { checked: 500, flagged: 10 }),
          point(from + 3 * HOUR, from + 4 * HOUR, { checked: 500, flagged: 20 }),
          point(from + 9 * HOUR, from + 11 * HOUR, { checked: 500, flagged: 30 }),
          point(from + 11 * HOUR, from + 14 * HOUR, { checked: 50, flagged: 1, open: true }),
        ],
      }),
    );

    const { solid, dashed } = seriesPaths(screen.getByRole("region", { name: "Frustration" }));
    // The rate line: one move per run of joined steps, so the first two join and the third stands apart.
    const line = solid.find((d) => d.includes("V"))!;
    expect(line.match(/M/g)).toHaveLength(2);
    expect(line.match(/V/g)).toHaveLength(1);
    // The open point starts where the third ended, so its connector is dashed with it.
    const open = dashed.find((d) => d.startsWith("M") && d.includes("V"))!;
    expect(open).toBeTruthy();
  });

  // Bug: a case bar that goes nowhere, or that goes to the finding instead of the case.
  it("opens the case page from its bar in the cases strip", () => {
    renderCard(card({ cases: { open_cases: 1, spans: [span({ case_id: "case/7" })] } }));

    fireEvent.click(screen.getByRole("link", { name: /Opens C-43/ }));

    expect(currentLocation()).toBe("/orgs/acme/projects/default/cases/case%2F7");
  });

  // Bug: the hover text of a case bar missing, so the reader must open the case to learn what it was.
  it("names the case on hover", () => {
    renderCard(card({ cases: { open_cases: 1, spans: [span({})] } }));

    fireEvent.mouseEnter(screen.getByRole("link", { name: /Opens C-43/ }));

    const tip = screen.getByRole("tooltip");
    expect(tip.textContent).toContain("Opens C-43");
    expect(tip.textContent).toContain("Users on support_agent.reply grew frustrated");
    expect(tip.textContent).toContain("Open since Oct 4");
  });

  // Bug: more than three rows squeezed into the strip, or the extra cases silently dropped.
  it("uses three rows at most: two cases and how many more Triage holds", () => {
    const spans = [1, 2, 3, 4, 5].map((i) =>
      span({ case_id: `case-${i}`, case_reference: `C-${i}`, start_at: `2026-10-0${i}T00:00:00Z` }),
    );
    renderCard(card({ cases: { open_cases: 5, spans } }));

    expect(screen.getAllByRole("link", { name: /^Opens C-/ })).toHaveLength(2);
    expect(screen.getByRole("link", { name: "+3 more in Triage" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/triage",
    );
  });

  it("says so when no case ran in the range", () => {
    renderCard(card({}));
    expect(screen.getByText("None in this range")).toBeTruthy();
  });

  // Bug: hover text reachable by mouse only, so a keyboard reader cannot read any point's numbers.
  it("reads a point's numbers from the keyboard: the newest first, then one point back per arrow", () => {
    renderCard(card({}));

    const chart = screen.getByRole("group", { name: /Frustration, last 28 days, 28 points/ });
    fireEvent.focus(chart);
    expect(screen.getByRole("tooltip").textContent).toContain("Oct 8, 00:00-14:00 UTC, still filling");
    expect(screen.getByRole("tooltip").textContent).toContain("8 of 100 conversations");

    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(screen.getByRole("tooltip").textContent).toContain("Oct 7, 00:00-24:00 UTC");
    expect(screen.getByRole("tooltip").textContent).toContain("4 of 100 conversations");

    fireEvent.keyDown(chart, { key: "Home" });
    expect(screen.getByRole("tooltip").textContent).toContain("Sep 11, 00:00-24:00 UTC");
    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(screen.getByRole("tooltip").textContent).toContain("Sep 11, 00:00-24:00 UTC");
    fireEvent.keyDown(chart, { key: "End" });
    expect(screen.getByRole("tooltip").textContent).toContain("Oct 8, 00:00-14:00 UTC");
  });

  // Bug: a hovered point past the end of new, shorter data (another range, a refetch) crashed the card.
  it("drops the hover when the card gets fewer points than the hovered one", () => {
    const few = card({ points: ratePoints().slice(-3) });
    function Swap() {
      const [c, setC] = useState(card({}));
      return (
        <>
          <button onClick={() => setC(few)}>swap</button>
          <ChartCard card={c} range={RANGE} basePath="/orgs/acme/projects/default" />
        </>
      );
    }
    renderRoute(<Swap />, {
      route: "/orgs/acme/projects/default/classifiers",
      parent: "/orgs/:orgSlug/projects/:projectSlug",
      path: "classifiers",
    });

    const chart = screen.getByRole("group", { name: /28 points/ });
    fireEvent.focus(chart);
    fireEvent.keyDown(chart, { key: "Home" });
    fireEvent.keyDown(chart, { key: "ArrowRight" });
    fireEvent.keyDown(chart, { key: "ArrowRight" });
    fireEvent.keyDown(chart, { key: "ArrowRight" });
    fireEvent.keyDown(chart, { key: "ArrowRight" });
    expect(screen.getByRole("tooltip").textContent).toContain("Sep 15, 00:00-24:00 UTC");

    fireEvent.click(screen.getByRole("button", { name: "swap" }));
    expect(screen.getByRole("group", { name: /3 points/ })).toBe(chart);
    expect(screen.queryByRole("tooltip")).toBeNull();
    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(screen.getByRole("tooltip").textContent).toContain("Oct 7, 00:00-24:00 UTC");
  });

  // Bug: the arrows move the crosshair, but a screen reader hears nothing as the point changes.
  it("tells a screen reader each point the arrows move to", () => {
    renderCard(card({}));

    const chart = screen.getByRole("group", { name: /Frustration/ });
    expect(chart.getAttribute("aria-roledescription")).toBe("chart");
    expect(chart.getAttribute("aria-label")).toContain("The arrow keys move through the points.");
    fireEvent.focus(chart);
    fireEvent.keyDown(chart, { key: "ArrowLeft" });

    const live = within(screen.getByRole("region", { name: "Frustration" })).getByRole("status");
    expect(live.textContent).toContain("Oct 7, 00:00-24:00 UTC");
    expect(live.textContent).toContain("4 of 100 conversations");
    expect(screen.getByRole("group", { name: /Frustration/, description: /Oct 7/ })).toBe(chart);
  });
});
