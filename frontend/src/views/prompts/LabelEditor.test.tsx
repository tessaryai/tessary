// SPDX-License-Identifier: Apache-2.0
/*
 * Deployment labels decide which version an application fetches. The bugs worth catching are `latest` offered as a
 * label to set or sent, a label move that does not say it moves, production removed with no warning, a bad label
 * accepted, the wrong set sent, and a saved change that leaves the page stale.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { LabelRef } from "../../api/types";
import { api } from "../../test/api";
import { promptVersion } from "../../test/fixtures";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import { renderRoute } from "../../test/render";
import { LabelEditor } from "./LabelEditor";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const ALL: LabelRef[] = [
  { label: "production", version: 2 },
  { label: "staging", version: 1 },
  { label: "canary", version: 1 },
];

function open(version = 2, labels = ["production", "latest"]) {
  const { queryClient } = renderRoute(
    <div>
      <p>outside</p>
      <LabelEditor promptId="p-1" version={version} labels={labels} allLabels={ALL} />
    </div>,
  );
  fireEvent.click(screen.getByRole("button", { name: "Labels" }));
  return { queryClient, panel: screen.getByRole("dialog", { name: `Deployment labels for version ${version}` }) };
}

const sw = (name: string) => screen.getByRole("switch", { name });

describe("the label list", () => {
  // Bug: `latest` drawn as a switch, or production and staging missing until some version carries them.
  it("offers production and staging, then every other label, and never latest", () => {
    open(2, ["production", "latest"]);

    expect(screen.getAllByRole("switch").map((s) => s.getAttribute("aria-label"))).toEqual(["production", "staging", "canary"]);
    expect(sw("production").getAttribute("aria-checked")).toBe("true");
    expect(sw("staging").getAttribute("aria-checked")).toBe("false");
  });

  // Bug: turning on a label that sits on another version reads as adding it, not moving it.
  it("says a label on another version moves here", () => {
    open();
    expect(screen.queryByText("On version 1. Saving moves it here.")).toBeNull();

    fireEvent.click(sw("staging"));

    expect(screen.getByText("On version 1. Saving moves it here.")).toBeTruthy();
  });

  // Bug: production removed from its version with no word that fetches by the default label start failing.
  it("warns when no version will carry production", () => {
    open();

    fireEvent.click(sw("production"));

    expect(screen.getByText("No version will carry production. Applications that fetch it get a not-found error.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Save labels" })).toBeTruthy();
  });
});

describe("adding a label", () => {
  // Bug: a label the server refuses, `latest`, or a duplicate row added.
  it.each([
    ["Prod", "Use 1 to 36 lowercase letters, digits, _ . or -."],
    ["a".repeat(37), "Use 1 to 36 lowercase letters, digits, _ . or -."],
    ["latest", "latest is derived from the highest version and cannot be set."],
    ["canary", "This label is already in the list."],
  ])("refuses %s", (label, error) => {
    open();
    fireEvent.change(screen.getByRole("textbox", { name: "New label" }), { target: { value: label } });
    fireEvent.click(screen.getByRole("button", { name: "Add label" }));

    expect(screen.getByText(error)).toBeTruthy();
    expect(screen.getAllByRole("switch")).toHaveLength(3);
  });

  // Bug: a new label added off, so saving drops it, or the box keeps the text for a second add.
  it("adds a valid label switched on and clears the box", () => {
    open();
    const box = screen.getByRole("textbox", { name: "New label" }) as HTMLInputElement;
    fireEvent.change(box, { target: { value: " beta-1 " } });
    fireEvent.click(screen.getByRole("button", { name: "Add label" }));

    expect(sw("beta-1").getAttribute("aria-checked")).toBe("true");
    expect(box.value).toBe("");
  });
});

describe("saving", () => {
  // Bug: a rollback that sends latest, sends the old set, or forgets the list and detail reads.
  it("promotes an older version to production and refreshes the prompt's reads", async () => {
    api.setPromptVersionLabels.mockResolvedValue(promptVersion({ version: 1 }));
    const { queryClient } = open(1, []);
    seedPromptReads(queryClient);

    fireEvent.click(sw("production"));
    expect(screen.getByText("On version 2. Saving moves it here.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Promote to production" }));

    expect(await screen.findByText("Version 1 is now production")).toBeTruthy();
    expect(api.setPromptVersionLabels).toHaveBeenCalledWith("p-1", 1, ["production"]);
    expect(promptReadsStale(queryClient)).toEqual([true, true, true]);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  // Bug: `latest` sent back with the set the server refuses with LATEST_RESERVED.
  it("saves the set without latest", async () => {
    api.setPromptVersionLabels.mockResolvedValue(promptVersion());
    open(2, ["production", "latest"]);

    fireEvent.click(sw("staging"));
    fireEvent.click(screen.getByRole("button", { name: "Save labels" }));

    expect(await screen.findByText("Labels saved")).toBeTruthy();
    expect(api.setPromptVersionLabels).toHaveBeenCalledWith("p-1", 2, ["production", "staging"]);
  });

  // Bug: an unchanged set written again, moving nothing but touching the prompt.
  it("disables saving until something changes", () => {
    open();
    const save = screen.getByRole("button", { name: "Save labels" }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);

    fireEvent.click(sw("staging"));
    fireEvent.click(sw("staging"));

    expect(save.disabled).toBe(true);
  });

  // Bug: a refused save closes the panel silently, so the reader believes the label moved.
  it("says why a save failed and keeps the panel open", async () => {
    api.setPromptVersionLabels.mockRejectedValue(new Error("PROMPTS.CONCURRENT_WRITE: retry"));
    open();

    fireEvent.click(sw("staging"));
    fireEvent.click(screen.getByRole("button", { name: "Save labels" }));

    expect(await screen.findByText("Could not save labels")).toBeTruthy();
    expect(screen.getByText("PROMPTS.CONCURRENT_WRITE: retry")).toBeTruthy();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });
});

describe("closing", () => {
  // Bug: the panel stuck open over the page, or Cancel saving the edit.
  it.each([
    ["Escape", () => fireEvent.keyDown(document, { key: "Escape" })],
    ["an outside click", () => fireEvent.mouseDown(screen.getByText("outside"))],
    ["Cancel", () => fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }))],
    ["the Labels button", () => fireEvent.click(screen.getByRole("button", { name: "Labels" }))],
  ])("closes on %s without saving", async (_, close) => {
    open();
    fireEvent.click(sw("staging"));

    close();

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(api.setPromptVersionLabels).not.toHaveBeenCalled();
  });

  // Bug: an edit abandoned by closing comes back on the next open.
  it("opens fresh after a cancel", () => {
    open();
    fireEvent.click(sw("staging"));
    fireEvent.keyDown(document, { key: "Escape" });

    fireEvent.click(screen.getByRole("button", { name: "Labels" }));

    expect(sw("staging").getAttribute("aria-checked")).toBe("false");
  });
});
