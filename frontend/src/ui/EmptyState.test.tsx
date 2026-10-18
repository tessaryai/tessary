// SPDX-License-Identifier: Apache-2.0
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { EmptyState } from "./EmptyState";

describe("EmptyState", () => {
  // Catches an icon that is dropped, drawn below the title, or drawn in a colour other than muted grey.
  it("rendersTheIconAboveTheTitleWhenGiven", () => {
    render(<EmptyState icon={<svg role="img" aria-label="Grid" />} title="No widgets yet" body="Add one to start." />);

    const icon = screen.getByRole("img", { name: "Grid" });
    const title = screen.getByRole("heading", { name: "No widgets yet" });
    expect(icon.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(icon.parentElement!.className.split(" ")).toContain("text-muted");
  });

  // Catches the new prop changing every existing empty state, which renders no icon.
  it("rendersWithoutAnIconAsBefore", () => {
    render(<EmptyState title="Nothing here" body="Nothing ran in this window." action={<button>Add</button>} />);

    expect(screen.getByRole("heading", { name: "Nothing here" })).toBeTruthy();
    expect(screen.getByText("Nothing ran in this window.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Add" })).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
  });
});
