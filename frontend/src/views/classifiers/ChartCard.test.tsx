// SPDX-License-Identifier: Apache-2.0
/*
 * One chart card as the reader meets it: the pills, the headline, the baseline or arming line, the cases strip and
 * the hover text. The rules behind the numbers are tested in chartRules.test.ts; these tests catch the card that
 * draws the wrong thing from right numbers.
 */
import { fireEvent, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ChartCard as Card, ChartCaseSpan, ChartDay } from "../../api/types";
import { currentLocation, renderRoute } from "../../test/render";
import { ChartCard } from "./ChartCard";

function datesEnding(last: string, n: number): string[] {
  const end = Date.parse(`${last}T00:00:00Z`);
  return Array.from({ length: n }, (_, i) => new Date(end - (n - 1 - i) * 86_400_000).toISOString().slice(0, 10));
}

const rateDays = (n: number): ChartDay[] =>
  datesEnding("2026-10-08", n).map((date, i) => ({
    date,
    checked: 100,
    flagged: i === n - 1 ? 8 : 4,
    n: null,
    p50: null,
    p95: null,
    count: null,
    total: null,
  }));

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
    days: rateDays(28),
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

const renderCard = (c: Card) =>
  renderRoute(<ChartCard card={c} basePath="/orgs/acme/projects/default" />, {
    route: "/orgs/acme/projects/default/classifiers",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "classifiers",
  });

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
    expect(screen.getByText("Per day, UTC")).toBeTruthy();
  });

  // Bug: the "opens a finding" line drawn for an arming window that is not a day, where a daily bar cannot show it.
  it("draws the arming line only for a one-day window", () => {
    const countDays = datesEnding("2026-10-08", 7).map((date) => ({
      date,
      checked: null,
      flagged: null,
      n: null,
      p50: null,
      p95: null,
      count: 1,
      total: 1,
    }));
    const secret = (window_seconds: number) =>
      card({
        classifier_key: "secret_leak",
        name: "Secret Leak",
        kind: "count",
        unit: "count",
        baseline: null,
        headline: { value: 7, delta: null },
        arming: { threshold: 2, window_seconds, basis: "event_count", confidence: "high" },
        days: countDays,
      });

    const { unmount } = renderCard(secret(86_400));
    expect(screen.getByText("2 in a day opens a finding")).toBeTruthy();
    unmount();

    renderCard(secret(3_600));
    expect(screen.getByText("Detections per day")).toBeTruthy();
    expect(screen.queryByText(/opens a finding/)).toBeNull();
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

  // Bug: hover text reachable by mouse only, so a keyboard reader cannot read any day's numbers.
  it("reads a day's numbers from the keyboard: today first, then one day back per arrow", () => {
    renderCard(card({}));

    const chart = screen.getByRole("group", { name: /Frustration/ });
    fireEvent.focus(chart);
    expect(screen.getByRole("tooltip").textContent).toContain("Oct 8");
    expect(screen.getByRole("tooltip").textContent).toContain("8 of 100 conversations");

    fireEvent.keyDown(chart, { key: "ArrowLeft" });
    expect(screen.getByRole("tooltip").textContent).toContain("Oct 7");
    expect(screen.getByRole("tooltip").textContent).toContain("4 of 100 conversations");
  });

  // Bug: the arrows move the crosshair, but a screen reader hears nothing as the day changes.
  it("tells a screen reader each day the arrows move to", () => {
    renderCard(card({}));

    const chart = screen.getByRole("group", { name: /Frustration/ });
    expect(chart.getAttribute("aria-roledescription")).toBe("chart");
    fireEvent.focus(chart);
    fireEvent.keyDown(chart, { key: "ArrowLeft" });

    const live = within(screen.getByRole("region", { name: "Frustration" })).getByRole("status");
    expect(live.textContent).toContain("Oct 7");
    expect(live.textContent).toContain("4 of 100 conversations");
    expect(screen.getByRole("group", { name: /Frustration/, description: /Oct 7/ })).toBe(chart);
  });
});
