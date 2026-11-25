// SPDX-License-Identifier: Apache-2.0
/*
 * The geometry of an issue's retention chart (spec 8.9). The wire sends weeks 1 to n (at most 8); week 0 is the hit
 * day, where both lines start at 100%. The share of week w drops at the end of week w, so a line moves across week w at
 * the share of the week before, drops at x(w) and ends there at the last week with data.
 */
import type { EndUserCurvePoint } from "../../../api/types";
import { stepPaths, type Step } from "../../classifiers/chartRules";

export const WEEKS = 8;

const TICK = 0.25;
const HIGHEST_FLOOR = 0.5;
const END_PAD = 6;

export type PlotBox = { left: number; right: number; top: number; bottom: number };

export type RetentionShapes = {
  floor: number;
  ticks: { value: number; y: number }[];
  hit: string;
  similar: string;
  loss: string;
  band: string;
  end: { x: number; hitY: number; similarY: number };
  gap: { x: number; y: number };
};

/**
 * @param line the height in pixels a text line of the chart needs: end labels keep this far apart, and the gap label
 *   sits between the lines only when the gap holds it with room to spare.
 */
export function retentionShapes(curve: EndUserCurvePoint[], box: PlotBox, line: number): RetentionShapes {
  const n = curve.length;
  const hit = [1, ...curve.map((p) => p.hit)];
  const similar = [1, ...curve.map((p) => p.similar)];
  const lowest = Math.min(...hit, ...similar);
  const floor = Math.min(HIGHEST_FLOOR, Math.floor(lowest / TICK) * TICK);

  const x = (week: number) => box.left + (week / WEEKS) * (box.right - box.left);
  const y = (share: number) => box.top + ((1 - Math.max(share, floor)) / (1 - floor)) * (box.bottom - box.top);

  const path = (shares: number[]) =>
    stepPaths(
      shares.map((v, week): Step => ({ start: week, end: Math.min(week + 1, n), v, open: false })),
      x,
      y,
    ).solid;
  const at = (week: number, share: number) => `${x(week).toFixed(1)},${y(share).toFixed(1)}`;
  const corners = (shares: number[]) => [
    at(0, shares[0]),
    ...shares.slice(1).flatMap((v, i) => [at(i + 1, shares[i]), at(i + 1, v)]),
  ];
  const area = (upper: number[], lower: number[]) => [...corners(upper), ...corners(lower).reverse()].join(" ");

  const ticks: { value: number; y: number }[] = [];
  for (let value = 1; value >= floor; value -= TICK) ticks.push({ value, y: y(value) });

  let hitY = y(hit[n]);
  let similarY = y(similar[n]);
  if (Math.abs(hitY - similarY) < line) {
    const mid = (hitY + similarY) / 2;
    const down = hitY >= similarY ? 1 : -1;
    hitY = mid + (down * line) / 2;
    similarY = mid - (down * line) / 2;
  }

  const gapWeek = Math.min(n, Math.max(2, Math.round(n * 0.6)));
  const gapTop = y(similar[gapWeek - 1]);
  const gapBottom = y(hit[gapWeek - 1]);
  const gapY = gapBottom - gapTop < line + 8 ? gapBottom + line : (gapTop + gapBottom) / 2;

  return {
    floor,
    ticks,
    hit: path(hit),
    similar: path(similar),
    loss: area(similar, hit),
    band: area([1, ...curve.map((p) => p.band_high)], [1, ...curve.map((p) => p.band_low)]),
    end: { x: x(n) + END_PAD, hitY, similarY },
    gap: { x: x(gapWeek - 0.5), y: gapY },
  };
}
