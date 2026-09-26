// SPDX-License-Identifier: Apache-2.0
/*
 * How long ago, as a staleness signal. The bugs worth catching: a unit boundary one off, and a time just
 * ahead of the browser's clock read as a negative age.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { relativeTime } from "./relativeTime";

const NOW = new Date("2026-09-25T12:00:00Z");
const ago = (ms: number) => new Date(NOW.getTime() - ms).toISOString();

beforeEach(() => {
  vi.useFakeTimers({ now: NOW, toFake: ["Date"] });
});
afterEach(() => {
  vi.useRealTimers();
});

describe("relativeTime", () => {
  it.each([
    [59_000, "59s ago"],
    [60_000, "1m ago"],
    [59 * 60_000, "59m ago"],
    [60 * 60_000, "1h ago"],
    [23 * 3_600_000, "23h ago"],
    [24 * 3_600_000, "1d ago"],
    [-5_000, "just now"],
  ])("reads an age of %i ms as %s", (ms, words) => {
    expect(relativeTime(ago(ms))).toBe(words);
  });

  it("dashes a time it was not given", () => {
    expect(relativeTime(null)).toBe("—");
  });
});
