// SPDX-License-Identifier: Apache-2.0

/** The index of the point whose span holds `px`, else the one nearest it. */
export function nearestPoint(spans: { a: number; b: number }[], px: number): number | null {
  let best: number | null = null;
  let gap = Infinity;
  spans.forEach(({ a, b }, i) => {
    const d = px < a ? a - px : px > b ? px - b : 0;
    if (d < gap) {
      gap = d;
      best = i;
    }
  });
  return best;
}
