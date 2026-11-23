// SPDX-License-Identifier: Apache-2.0
/*
 * Deployment labels decide which version an application fetches. The bugs worth catching are `latest` offered as a
 * label to set or sent, a label move that does not say it moves, production removed with no warning, a promotion or
 * a rollback that does not say what agents get next, a bad label accepted, the wrong set sent, and a saved change
 * that leaves the page stale.
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

function open(version = 2, labels = ["production", "latest"], { all = ALL, promote = false } = {}) {
  const { queryClient } = renderRoute(
    <LabelEditor promote={promote} promptId="p-1" version={version} labels={labels} allLabels={all} latestVersion={2} />,
  );
  fireEvent.click(screen.getByRole("button", { name: promote ? "Promote to production" : "Deployment labels" }));
  return { queryClient, panel: screen.getByRole("dialog", { name: `Deployment labels for version ${version}` }) };
}

const box = (name: string) => screen.getByRole("checkbox", { name }) as HTMLInputElement;
const sw = box;

describe("the label list", () => {
  // Bug: `latest` offered as a label to set, or production and staging missing until some version carries them.
  it("offers production and staging, then every other label, and shows latest without letting it be set", () => {
    open(2, ["production", "latest"]);

    expect(screen.getAllByRole("checkbox").map((s) => s.getAttribute("aria-label"))).toEqual(
      ["production", "staging", "canary", "latest"],
    );
    expect(box("production").checked).toBe(true);
    expect(box("staging").checked).toBe(false);
    expect(box("latest").disabled).toBe(true);
    expect(box("latest").checked).toBe(true);
    expect(screen.getByText("The newest version")).toBeTruthy();
  });

  // Bug: turning on a label that sits on another version reads as adding it, not moving it.
  it("says where each label is, and that a label on another version moves here", () => {
    open();
    expect(screen.getByText("On this version")).toBeTruthy();
    expect(screen.getAllByText("On version 1")).toHaveLength(2);
    expect(screen.queryByText("Moves from version 1")).toBeNull();

    fireEvent.click(sw("staging"));

    expect(screen.getByText("Moves from version 1")).toBeTruthy();
  });

  // Bug: production removed from its version with no word that fetches by the default label start failing.
  it("warns when no version will carry production", () => {
    open();

    fireEvent.click(sw("production"));

    expect(screen.getByText("No version will carry production. Applications that fetch it get a not-found error.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Save labels" })).toBeTruthy();
  });
});

describe("what agents get next", () => {
  // Bug: production moves with no word on when agents see it or how to undo it.
  it("says agents get this version on their next fetch, and how to roll back", () => {
    open(3, ["staging", "latest"], { all: [{ label: "production", version: 2 }, { label: "staging", version: 3 }], promote: true });

    expect(box("production").checked).toBe(true);
    expect(screen.getByText("Moves from version 2")).toBeTruthy();
    expect(
      screen.getByText(
        "Agents that fetch production get version 3 on their next fetch. The Python SDK caches a prompt for 60 seconds, and on more than one server node the server's own cache can add its TTL. To roll back, move production back to version 2.",
      ),
    ).toBeTruthy();
  });

  // Bug: a rollback reads as if the newer version is lost.
  it("says the newer version stays when production moves back to an older one", () => {
    open(1, [], { promote: true });

    expect(
      screen.getByText(
        "Agents that fetch production get version 1 on their next fetch. The Python SDK caches a prompt for 60 seconds, and on more than one server node the server's own cache can add its TTL. Version 2 does not change, so you can promote it again.",
      ),
    ).toBeTruthy();
  });

  // Bug: the first production promotion promises a way back that does not exist.
  it("says only when agents see it when no version carried production", () => {
    open(1, [], { all: [], promote: true });

    expect(
      screen.getByText("Agents that fetch production get version 1 on their next fetch. The Python SDK caches a prompt for 60 seconds, and on more than one server node the server's own cache can add its TTL."),
    ).toBeTruthy();
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
    fireEvent.change(screen.getByRole("textbox", { name: "New deployment label" }), { target: { value: label } });
    fireEvent.click(screen.getByRole("button", { name: "Add label" }));

    expect(screen.getByText(error)).toBeTruthy();
    expect(screen.getAllByRole("checkbox")).toHaveLength(4);
  });

  // Bug: a new label added off, so saving drops it, or the box keeps the text for a second add.
  it("adds a valid label ticked and clears the box", () => {
    open();
    const input = screen.getByRole("textbox", { name: "New deployment label" }) as HTMLInputElement;
    fireEvent.change(input, { target: { value: " beta-1 " } });
    fireEvent.click(screen.getByRole("button", { name: "Add label" }));

    expect(box("beta-1").checked).toBe(true);
    expect(input.value).toBe("");
  });
});

// Bug: Enter in the new-label box submits nothing, or submits the surrounding form.
it("adds a label on Enter", () => {
  open();
  const input = screen.getByRole("textbox", { name: "New deployment label" });
  fireEvent.change(input, { target: { value: "beta" } });

  fireEvent.keyDown(input, { key: "Enter" });

  expect(box("beta").checked).toBe(true);
});

describe("saving", () => {
  // Bug: a rollback that sends latest, sends the old set, or forgets the list and detail reads.
  it("promotes an older version to production and refreshes the prompt's reads", async () => {
    api.setPromptVersionLabels.mockResolvedValue(promptVersion({ version: 1 }));
    const { queryClient } = open(1, []);
    seedPromptReads(queryClient);

    fireEvent.click(sw("production"));
    expect(screen.getByText("Moves from version 2")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Promote to production" }));

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
  // Bug: the dialog stuck open over the page, or closing it saving the edit.
  it.each([
    ["Escape", () => screen.getByRole("dialog").dispatchEvent(new Event("cancel", { cancelable: true }))],
    ["Cancel", () => fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }))],
    ["the close button", () => fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Close" }))],
  ])("closes on %s without saving", async (_, close) => {
    open();
    fireEvent.click(sw("staging"));

    close();

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(api.setPromptVersionLabels).not.toHaveBeenCalled();
  });

  // Bug: an edit abandoned by closing comes back on the next open.
  it("opens fresh after a cancel", async () => {
    open();
    fireEvent.click(sw("staging"));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    fireEvent.click(screen.getByRole("button", { name: "Deployment labels" }));

    expect(box("staging").checked).toBe(false);
  });
});
