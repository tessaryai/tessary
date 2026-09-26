// SPDX-License-Identifier: Apache-2.0

/** Spend as the vitals surfaces print it: whole dollars from $100, cents below. */
export function usd(n: number): string {
  return n >= 100 ? `$${Math.round(n)}` : `$${n.toFixed(2)}`;
}
