// SPDX-License-Identifier: Apache-2.0
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Tooltip } from "./Tooltip";

describe("Tooltip", () => {
  // Catches lines lost or reordered, or a headline that no longer stands out from the detail lines.
  it("hasTheTooltipRoleAndPrintsTheFirstLineAsTheHeadline", () => {
    render(<Tooltip left={40} top="4px" transform="translateX(-50%)" lines={["Oct 12, 14:00", "checkout: $1.20", "search: —"]} />);

    const tip = screen.getByRole("tooltip");
    expect(Array.from(tip.children).map((line) => line.textContent)).toEqual([
      "Oct 12, 14:00",
      "checkout: $1.20",
      "search: —",
    ]);
    expect(screen.getByText("Oct 12, 14:00").className.split(" ")).toContain("text-fg");
    expect(screen.getByText("checkout: $1.20").className.split(" ")).toContain("text-muted");
    expect(screen.getByText("search: —").className.split(" ")).toContain("text-muted");
    expect(tip.style.left).toBe("40%");
    expect(tip.style.top).toBe("4px");
    expect(tip.style.transform).toBe("translateX(-50%)");
  });
});
