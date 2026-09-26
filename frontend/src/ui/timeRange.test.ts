// SPDX-License-Identifier: Apache-2.0
/*
 * The time-range fields' wall-clock conversion. The bug worth catching: an unreadable time from the URL
 * filling the field with "NaN-NaN-…" rather than leaving it empty.
 */
import { describe, expect, it } from "vitest";
import { isoToLocal, localToIso } from "./timeRange";

describe("isoToLocal", () => {
  it("round-trips a time through the field's wall-clock form", () => {
    const iso = new Date(2026, 8, 25, 9, 5).toISOString();
    expect(isoToLocal(iso)).toBe("2026-09-25T09:05");
    expect(localToIso("2026-09-25T09:05")).toBe(iso);
  });

  it("leaves the field empty for no time or an unreadable one", () => {
    expect(isoToLocal(null)).toBe("");
    expect(isoToLocal("yesterday-ish")).toBe("");
  });
});
