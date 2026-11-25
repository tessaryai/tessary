// SPDX-License-Identifier: Apache-2.0
/*
 * The geometry of the retention chart on a plot box where week w sits at x = w and, while the axis floor is 50%, a
 * retained share s sits at y = (1 - s) * 100. Every expected path is written by hand from the board values in the
 * fixtures and the stepPaths rule: each week moves across, drops to its new share and holds it to the next week.
 */
import { describe, expect, it } from "vitest";
import type { EndUserCurvePoint } from "../../../api/types";
import { C118_NUMBERS, C121_NUMBERS } from "../../../test/usersFixtures";
import { retentionShapes } from "./retentionRules";

const BOX = { left: 0, right: 8, top: 0, bottom: 50 };
const LINE = 14;

const point = (week: number, hit: number, similar: number): EndUserCurvePoint => ({
  week,
  hit,
  similar,
  band_low: hit,
  band_high: hit,
  at_risk_hit: 50,
  at_risk_similar: 500,
});

describe("retentionShapes", () => {
  // C1. Bug: a line drawn from the wrong field (the band, or similar for hit), a share not mapped to its week, or a
  // curve that does not start at 100% on the hit day because the wire sends no week 0.
  it("stepPath_matchesSurvivalValues", () => {
    const s = retentionShapes(C118_NUMBERS.curve!, BOX, LINE);

    expect(s.hit).toBe(
      "M0.0 0.0H1.0H1.0V12.0H2.0H2.0V21.0H3.0H3.0V28.0H4.0H4.0V33.0H5.0H5.0V37.0H6.0H6.0V40.0H7.0H7.0V42.0H8.0H8.0V43.0H8.0",
    );
    expect(s.similar).toBe(
      "M0.0 0.0H1.0H1.0V3.0H2.0H2.0V6.0H3.0H3.0V8.0H4.0H4.0V10.0H5.0H5.0V11.0H6.0H6.0V12.0H7.0H7.0V13.0H8.0H8.0V14.0H8.0",
    );
    expect(s.end.x).toBe(14);
    expect(s.end.hitY).toBeCloseTo(43);
    expect(s.end.similarY).toBeCloseTo(14);
  });

  // C4. Bug: a curve with fewer weeks than the axis is stretched to the right edge, or padded with steps it has no
  // data for, so a 6-week issue reads like an 8-week one.
  it("shortCurve_stopsAtLastWeekWithData", () => {
    const s = retentionShapes(C121_NUMBERS.curve!, BOX, LINE);

    expect(s.hit).toBe(
      "M0.0 0.0H1.0H1.0V5.0H2.0H2.0V9.0H3.0H3.0V12.0H4.0H4.0V14.0H5.0H5.0V16.0H6.0H6.0V17.0H6.0",
    );
    expect(s.hit.match(/V/g)).toHaveLength(6);
    expect(s.end.x).toBe(12);
  });

  // Bug: the loss area or the band is closed on the wrong side, so it fills under the axis instead of between the two
  // lines; or the band's low edge, which on the board sample falls to 49.2%, is drawn below the 50% axis line.
  it("lossArea_andBand_closeBetweenTheirEdges_andStayAboveTheFloor", () => {
    const s = retentionShapes(C118_NUMBERS.curve!, BOX, LINE);

    expect(s.loss.startsWith("0.0,0.0 1.0,0.0 1.0,3.0 2.0,3.0 ")).toBe(true);
    expect(s.loss).toContain(" 8.0,13.0 8.0,14.0 8.0,43.0 8.0,42.0 ");
    expect(s.loss.endsWith(" 1.0,12.0 1.0,0.0 0.0,0.0")).toBe(true);
    expect(s.band.startsWith("0.0,0.0 1.0,0.0 1.0,8.4 ")).toBe(true);
    expect(s.band).toContain(" 8.0,35.2 8.0,50.0 ");
    expect(s.band.endsWith(" 1.0,0.0 0.0,0.0")).toBe(true);
    const ys = s.band.split(" ").map((p) => Number(p.split(",")[1]));
    expect(Math.max(...ys)).toBe(50);
  });

  // Bug: a fixed 50% floor cuts off a line that falls lower, or the floor drops for a curve that never goes below 50%.
  it("yAxis_dropsBelowHalfOnlyWhenALineDoes", () => {
    const board = retentionShapes(C118_NUMBERS.curve!, BOX, LINE);
    expect(board.floor).toBe(0.5);
    expect(board.ticks).toEqual([
      { value: 1, y: 0 },
      { value: 0.75, y: 25 },
      { value: 0.5, y: 50 },
    ]);

    const deep = retentionShapes([point(1, 0.6, 0.9), point(2, 0.3, 0.85)], BOX, LINE);
    expect(deep.floor).toBe(0.25);
    expect(deep.ticks.map((t) => t.value)).toEqual([1, 0.75, 0.5, 0.25]);
    [0, 50 / 3, 100 / 3, 50].forEach((y, i) => expect(deep.ticks[i].y).toBeCloseTo(y));
  });

  // Bug: the two end labels print on top of each other when the lines end close together, or the spread swaps them so
  // the higher line gets the lower number.
  it("endLabels_spreadApart_andKeepTheirOrder", () => {
    const below = retentionShapes([point(1, 0.8, 0.82)], BOX, LINE);
    expect(below.end.hitY).toBeCloseTo(26);
    expect(below.end.similarY).toBeCloseTo(12);

    const above = retentionShapes([point(1, 0.82, 0.8)], BOX, LINE);
    expect(above.end.hitY).toBeCloseTo(12);
    expect(above.end.similarY).toBeCloseTo(26);
  });

  // Bug: the gap label sits on a line instead of between the lines, or in a gap too thin to hold it.
  it("gapLabel_sitsInTheGap_orUnderTheHitLine", () => {
    const wide = retentionShapes(C118_NUMBERS.curve!, BOX, LINE);
    expect(wide.gap.x).toBe(4.5);
    expect(wide.gap.y).toBeCloseTo(21.5);

    const thin = retentionShapes(C121_NUMBERS.curve!, BOX, LINE);
    expect(thin.gap.x).toBe(3.5);
    expect(thin.gap.y).toBeCloseTo(26);
  });
});
