// SPDX-License-Identifier: Apache-2.0
/*
 * The traces table's number formats, against the rules their doc comments state: tokens hold three
 * significant digits and promote across a unit rather than widen the column, latency names each unit
 * it needs and none it does not, cost keeps four decimals under a dollar, a date appears only when it
 * is not today (and a year only when it is not this one), and an absent number says whether it is
 * still arriving or genuinely nothing.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { formatCost, formatLatency, formatTokens, formatWhen } from "./index-data";
import { sessionDurationMs, sessionName, type SessionListItem } from "./session-index-data";

describe("formatTokens", () => {
  it.each([
    [null, "—"],
    [-5, "-5"],
    [0, "0"],
    [999, "999"],
    [1_234, "1.23k"],
    [9_994, "9.99k"],
    [9_995, "10.0k"],
    [12_345, "12.3k"],
    [99_949, "99.9k"],
    [99_950, "100k"],
    [123_456, "123k"],
    [999_499, "999k"],
    [999_500, "1.00M"],
    [1_234_567, "1.23M"],
    [2_500_000_000, "2.50B"],
  ])("%s -> %s", (n, text) => {
    expect(formatTokens(n)).toBe(text);
  });
});

describe("formatLatency", () => {
  it.each([
    [undefined, "—"],
    [-40, "0s"],
    [42_000, "42s"],
    [59_499, "59s"],
    [330_000, "5m 30s"],
    [8_130_000, "2h 15m 30s"],
    [267_330_000, "3d 2h 15m 30s"],
  ])("%s ms -> %s", (ms, text) => {
    expect(formatLatency(ms)).toBe(text);
  });
});

describe("formatCost", () => {
  it.each([
    [null, "—"],
    [0, "0"],
    [0.008, "0.0080"],
    [0.99994, "0.9999"],
    [1, "1.00"],
    [12.345, "12.35"],
  ])("%s -> %s", (usd, text) => {
    expect(formatCost(usd)).toBe(text);
  });
});

describe("formatWhen", () => {
  afterEach(() => vi.useRealTimers());

  it("shows a bare clock today, a date on another day, and a year only for another year", () => {
    vi.useFakeTimers({ now: new Date(2026, 8, 25, 18, 0, 0) });
    expect(formatWhen(new Date(2026, 8, 25, 14, 52, 11).toISOString())).toBe("14:52:11");
    expect(formatWhen(new Date(2026, 7, 8, 3, 4, 31).toISOString())).toBe("Aug 8 03:04:31");
    expect(formatWhen(new Date(2025, 11, 31, 23, 0, 0).toISOString())).toBe("Dec 31, 2025 23:00:00");
  });

  it("dashes a missing or unreadable time", () => {
    expect(formatWhen(null)).toBe("—");
    expect(formatWhen("not a date")).toBe("—");
  });
});

describe("session rows", () => {
  const session = (over: Partial<SessionListItem>) =>
    ({ id: "sess-1", started_at: "2026-09-25T10:00:00Z", last_activity_at: "2026-09-25T10:05:00Z", ...over }) as SessionListItem;

  it("names a session by its dominant call site, counting the others", () => {
    expect(sessionName(session({ dominant_call_site_id: null }))).toBe("sess-1");
    expect(sessionName(session({ dominant_call_site_id: "checkout", call_site_count: 3 }))).toBe("checkout +2");
    expect(sessionName(session({ dominant_call_site_id: "checkout", call_site_count: null }))).toBe("checkout");
  });

  it("times a session from its first trace to its last activity, and not at all when that is not forward", () => {
    expect(sessionDurationMs(session({}))).toBe(300_000);
    expect(sessionDurationMs(session({ last_activity_at: "2026-09-25T10:00:00Z" }))).toBeNull();
    expect(sessionDurationMs(session({ started_at: "garbage" }))).toBeNull();
  });
});
