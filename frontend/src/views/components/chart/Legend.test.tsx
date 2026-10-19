// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Legend } from "./Legend";

/** The colour marker is the first child of each entry; the entry's text is its label. */
const markerOf = (item: HTMLElement) => item.firstElementChild!.firstElementChild as HTMLElement;

describe("Legend", () => {
  // Catches rank order lost, a series drawn in the wrong colour, and the "other" fold drawn as a solid dot.
  it("listsItemsInOrderWithTheirColourAndADashedFoldMarker", () => {
    render(
      <Legend
        items={[
          { key: "checkout", label: "checkout", colour: "var(--color-series-1)" },
          { key: "search", label: "search", colour: "var(--color-series-2)" },
          { key: "summarise", label: "summarise", colour: "var(--color-series-3)" },
          { key: "__other__", label: "Other (7 more)", colour: "var(--color-series-other)", dashed: true },
        ]}
      />,
    );

    const items = within(screen.getByRole("list", { name: "Legend" })).getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual(["checkout", "search", "summarise", "Other (7 more)"]);
    expect(items.slice(0, 3).map((item) => markerOf(item).style.background)).toEqual([
      "var(--color-series-1)",
      "var(--color-series-2)",
      "var(--color-series-3)",
    ]);
    expect(items.slice(0, 3).map((item) => markerOf(item).style.borderTop)).toEqual(["", "", ""]);
    const fold = markerOf(items[3]);
    expect(fold.style.background).toBe("");
    expect(fold.style.borderTop).toBe("2px dashed var(--color-series-other)");
  });
});
