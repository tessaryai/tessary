// SPDX-License-Identifier: Apache-2.0
/*
 * The retention chart as a reader meets it in its three places: the issue row (P1), the case block (P4) and the issue
 * page (P2). Path geometry is proven in retentionRules.test.ts, so these tests read the chart's name, its text, the
 * at-risk table and which shapes are drawn. Expected strings are the board texts.
 */
import { cleanup, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { EndUserIssueNumbers } from "../../../api/types";
import { C118_NUMBERS, C121_NUMBERS, C123_NUMBERS } from "../../../test/usersFixtures";
import { RetentionChart, type RetentionChartProps, type ShownIssue } from "./RetentionChart";

const shown = (i: EndUserIssueNumbers): ShownIssue => ({ ...i, curve: i.curve! });

type Variant = RetentionChartProps["variant"];
const VARIANTS: Variant[] = ["row", "block", "page"];

function draw(issue: EndUserIssueNumbers, variant: Variant, caseRef = "C-118") {
  const props: RetentionChartProps =
    variant === "row" ? { issue: shown(issue), variant, caseRef } : { issue: shown(issue), variant };
  render(<RetentionChart {...props} />);
  return screen.getByRole("img");
}

const part = (chart: HTMLElement, name: string) => chart.querySelector(`[data-part="${name}"]`);
const texts = (cells: HTMLElement[]) => cells.map((c) => c.textContent);

describe("RetentionChart", () => {
  // C1. Bug: the end labels print the first week, a raw share (0.57) or swap the two lines' values, or the hit label
  // loses its red so a reader cannot tell which number is which line.
  it.each(VARIANTS)("stepPath_matchesSurvivalValues (%s)", (variant) => {
    const chart = draw(C118_NUMBERS, variant);

    expect(within(chart).getByText("57%").getAttribute("fill")).toBe("var(--color-error)");
    expect(within(chart).getByText("86%").getAttribute("fill")).toBe("var(--color-fg-secondary)");
  });

  // C2. Bug: the small charts in a list row or case block carry the band, the y axis or the week axis, which crowds
  // them past reading; or the issue page loses them.
  it("band_andAxis_onlyOnLargeChart", () => {
    const page = draw(C118_NUMBERS, "page");
    expect(part(page, "band")).not.toBeNull();
    expect(["100%", "75%", "50%"].map((t) => within(page).getByText(t).textContent)).toEqual(["100%", "75%", "50%"]);
    const table = screen.getByRole("table", { name: "at risk" });
    expect(texts(within(table).getAllByRole("columnheader"))).toEqual(
      ["wk 1", "wk 2", "wk 3", "wk 4", "wk 5", "wk 6", "wk 7", "wk 8"],
    );
    expect(screen.getByText("likely range of the hit line")).toBeTruthy();
    expect(screen.getByText("Still active, by week after the hit")).toBeTruthy();

    for (const variant of ["row", "block"] as const) {
      cleanup();
      const small = draw(C118_NUMBERS, variant);
      expect(part(small, "band")).toBeNull();
      expect(within(small).queryByText("100%")).toBeNull();
      expect(screen.queryByRole("table")).toBeNull();
      expect(screen.queryByText("wk 1")).toBeNull();
      expect(screen.queryByText("likely range of the hit line")).toBeNull();
    }
  });

  // C3. Bug: the at-risk counts are shifted a week, print without a thousands comma, or the rows lose their labels.
  it("atRiskRows", () => {
    draw(C118_NUMBERS, "page");
    const rows = within(screen.getByRole("table", { name: "at risk" })).getAllByRole("row");

    expect(within(rows[1]).getByRole("rowheader").textContent).toBe("at risk");
    expect(within(rows[2]).getByRole("rowheader").textContent).toBe("hit");
    expect(texts(within(rows[2]).getAllByRole("cell"))).toEqual(["96", "90", "82", "71", "57", "46", "33", "21"]);
    expect(within(rows[3]).getByRole("rowheader").textContent).toBe("similar");
    expect(texts(within(rows[3]).getAllByRole("cell"))).toEqual(
      ["1,012", "998", "981", "960", "944", "925", "903", "880"],
    );
  });

  // C4. Bug: a 6-week curve fills the weeks it has no data for with counts or end labels from week 8.
  it("shortCurve_stopsAtLastWeekWithData", () => {
    const chart = draw(C121_NUMBERS, "page");
    const rows = within(screen.getByRole("table", { name: "at risk" })).getAllByRole("row");

    expect(texts(within(rows[2]).getAllByRole("cell"))).toEqual(["66", "60", "52", "41", "30", "19", "", ""]);
    expect(within(chart).getByText("83%")).toBeTruthy();
    expect(within(chart).getByText("88%")).toBeTruthy();
  });

  // C5. Bug: an issue with no claimed loss gets the red loss area or a red label, which reads as a loss the verdict does
  // not claim; or the hit line turns grey when there is no claim.
  it.each(VARIANTS)("lossArea_andRedLabel_onlyWhenLossClaimed (%s)", (variant) => {
    const loss = draw(C118_NUMBERS, variant);
    expect(part(loss, "loss")).not.toBeNull();
    expect(part(loss, "hit")!.getAttribute("stroke")).toBe("var(--color-error)");
    const lossLabel = variant === "block" ? "about 23 lost" : "about 23 lost (11 to 35)";
    expect(within(loss).getByText(lossLabel).getAttribute("fill")).toBe("var(--color-error)");
    expect(screen.queryAllByText("user IDs lost")).toHaveLength(variant === "page" ? 1 : 0);

    for (const [issue, label] of [
      [C121_NUMBERS, "no clear loss"],
      [C123_NUMBERS, "always present · cannot compare"],
    ] as const) {
      cleanup();
      const plain = draw(issue, variant);
      expect(part(plain, "loss")).toBeNull();
      expect(part(plain, "hit")!.getAttribute("stroke")).toBe("var(--color-error)");
      expect(within(plain).getByText(label).getAttribute("fill")).toBe("var(--color-fg-secondary)");
      expect(screen.queryByText("user IDs lost")).toBeNull();
    }
  });

  // C6. Bug: the chart's name leaves out one of the two end values, so a screen reader hears one line only; or the row
  // chart loses the case it belongs to.
  it("ariaLabel_statesBothEndValues", () => {
    const board =
      "Still active by week after the hit, weeks 1 to 8: hit user IDs fall to 57%, similar user IDs not yet hit to 86%; about 23 user IDs lost (11 to 35)";
    expect(draw(C118_NUMBERS, "page").getAttribute("aria-label")).toBe(board);
    cleanup();
    expect(draw(C118_NUMBERS, "block").getAttribute("aria-label")).toBe(board);
    cleanup();
    expect(draw(C118_NUMBERS, "row").getAttribute("aria-label")).toBe(
      "C-118, still active 8 weeks after the hit: hit user IDs 57%, similar user IDs not yet hit 86%; about 23 lost (11 to 35)",
    );
    cleanup();
    expect(draw(C121_NUMBERS, "row", "C-121").getAttribute("aria-label")).toBe(
      "C-121, still active 6 weeks after the hit: hit user IDs 83%, similar user IDs not yet hit 88%; no clear loss",
    );
    cleanup();
    expect(draw(C121_NUMBERS, "page").getAttribute("aria-label")).toBe(
      "Still active by week after the hit, weeks 1 to 6: hit user IDs fall to 83%, similar user IDs not yet hit to 88%; no clear loss; if today's silences last, up to 14",
    );
  });
});
