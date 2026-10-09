// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { COLUMN_DEFS, SESSION_EXPAND_COLUMN_WIDTH, columnWidths, type ColumnDef } from "./index-columns";

const byKey = (...keys: string[]): ColumnDef[] =>
  keys.map((k) => {
    const col = COLUMN_DEFS.find((c) => c.key === k);
    if (!col) throw new Error(`no column ${k}`);
    return col;
  });

describe("columnWidths", () => {
  it("subtracts every fixed column from the flex columns' share", () => {
    // when 150 + latency 140 fixed; name 260 + input 280 + output 280 = 820 flexible.
    const widths = columnWidths(byKey("when", "name", "input", "output", "latency"));
    expect(widths[0]).toBe(150);
    expect(widths[4]).toBe(140);
    expect(widths[1]).toBe(`calc((100% - 290px) * ${(260 / 820).toFixed(6)})`);
    expect(widths[2]).toBe(`calc((100% - 290px) * ${(280 / 820).toFixed(6)})`);
    expect(widths[3]).toBe(widths[2]);
  });

  it("counts the pinned expand column as fixed width", () => {
    const widths = columnWidths(byKey("when", "name"), SESSION_EXPAND_COLUMN_WIDTH);
    expect(widths[1]).toBe("calc((100% - 190px) * 1.000000)");
  });

  it("falls back to declared widths when no flex column is visible", () => {
    expect(columnWidths(byKey("when", "latency", "cost"))).toEqual([150, 140, 90]);
  });
});
