// SPDX-License-Identifier: Apache-2.0
/*
 * The dashboard modals. The bugs worth catching: a blank name sent to the server, a rename that forgets the current
 * name, a delete that does not say what goes, a copy of the built-in dashboard that drops the edit just made, a copy
 * name that collides with one already taken, and a second click while a write is in flight.
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DEFAULT_DASHBOARD, DEFAULT_SUMMARY } from "../../test/metricsFixtures";
import { DashboardModals, type ModalState } from "./DashboardModals";
import type { MetricDashboardSummary } from "./wire";

const COST: MetricDashboardSummary = {
  id: "01JCOST", name: "Cost review", description: "Where the money goes", built_in: false, widget_count: 4,
  updated_at: "2026-10-17T09:00:00Z",
};

function renderModals(state: ModalState, over: { taken?: string[]; busy?: boolean } = {}) {
  const handlers = { onClose: vi.fn(), onCreate: vi.fn(), onRename: vi.fn(), onDelete: vi.fn(), onClone: vi.fn() };
  render(<DashboardModals state={state} taken={over.taken ?? ["Default", "Cost review"]} busy={over.busy ?? false} {...handlers} />);
  return handlers;
}

const dialog = () => screen.getByRole("dialog");
const name = () => within(dialog()).getByLabelText(/^Name/) as HTMLInputElement;
const description = () => within(dialog()).getByLabelText("Description") as HTMLTextAreaElement;
const button = (label: string) => within(dialog()).getByRole("button", { name: label }) as HTMLButtonElement;

describe("DashboardModals", () => {
  // Bug: a blank or padded name reaches the server.
  it("createNeedsANameAndEmitsIt", () => {
    const { onCreate } = renderModals({ kind: "create" });
    expect(within(dialog()).getByRole("heading", { name: "New dashboard" })).toBeTruthy();
    expect([name().value, description().value]).toEqual(["", ""]);
    expect(name().maxLength).toBe(80);
    expect(description().maxLength).toBe(500);
    expect(button("Create").disabled).toBe(true);

    fireEvent.change(name(), { target: { value: "   " } });
    expect(button("Create").disabled).toBe(true);
    fireEvent.change(name(), { target: { value: "  Cost review 2 " } });
    fireEvent.change(description(), { target: { value: "Spend only" } });
    fireEvent.click(button("Create"));
    expect(onCreate).toHaveBeenCalledWith("Cost review 2", "Spend only");
  });

  // Bug: rename opens blank, so saving without a change wipes the description.
  it("renameSeedsTheFieldsAndEmitsSaveChanges", () => {
    const { onRename } = renderModals({ kind: "rename", dashboard: COST });
    expect(within(dialog()).getByRole("heading", { name: "Rename dashboard" })).toBeTruthy();
    expect([name().value, description().value]).toEqual(["Cost review", "Where the money goes"]);
    fireEvent.change(name(), { target: { value: "Spend review" } });
    fireEvent.click(button("Save changes"));
    expect(onRename).toHaveBeenCalledWith("01JCOST", "Spend review", "Where the money goes");
  });

  // Bug: a delete that hides what it deletes, or reads as deleting the traces it counted.
  it("deleteNamesTheConsequenceAndEmits", () => {
    const { onDelete } = renderModals({ kind: "delete", dashboard: COST });
    expect(within(dialog()).getByRole("heading", { name: "Delete this dashboard?" })).toBeTruthy();
    expect(
      within(dialog()).getByText(
        "This deletes the dashboard and its widgets. The traces, calls and cases it counted stay.",
      ),
    ).toBeTruthy();
    expect(within(dialog()).queryByLabelText(/^Name/)).toBeNull();
    fireEvent.click(button("Delete dashboard"));
    expect(onDelete).toHaveBeenCalledWith("01JCOST");
  });

  // Bug: the copy made from an edit on the built-in dashboard starts without that edit.
  it("cloneForEditSeedsDefaultCopyAndEmitsTheDraft", () => {
    const draft = { ...DEFAULT_DASHBOARD.definition, widgets: DEFAULT_DASHBOARD.definition.widgets.slice(1) };
    const { onClone } = renderModals({ kind: "clone", source: DEFAULT_SUMMARY, definition: draft, reason: "edit" });
    expect(within(dialog()).getByRole("heading", { name: "Make an editable copy" })).toBeTruthy();
    expect(
      within(dialog()).getByText("The default dashboard is built in. Your copy starts with the change you just made."),
    ).toBeTruthy();
    expect(name().value).toBe("Default (copy)");
    expect(within(dialog()).queryByLabelText("Description")).toBeNull();
    fireEvent.click(button("Make a copy"));
    expect(onClone).toHaveBeenCalledWith("default", "Default (copy)", draft);
  });

  // Bug: the seeded copy name collides with one already taken and the server refuses it.
  it("cloneForDuplicateSeedsTheNextFreeCopyName", () => {
    const { onClone } = renderModals(
      { kind: "clone", source: COST, definition: null, reason: "duplicate" },
      { taken: ["Default", "Cost review", "Cost review (copy)"] },
    );
    expect(within(dialog()).getByRole("heading", { name: "Duplicate dashboard" })).toBeTruthy();
    expect(within(dialog()).getByText("The copy starts with the same widgets.")).toBeTruthy();
    expect(name().value).toBe("Cost review (copy 2)");
    fireEvent.click(button("Duplicate"));
    expect(onClone).toHaveBeenCalledWith("01JCOST", "Cost review (copy 2)", null);
  });

  // Bug: a second click while the write is in flight sends it twice.
  it("busyShowsLoadingOnThePrimaryAction", () => {
    renderModals({ kind: "rename", dashboard: COST }, { busy: true });
    expect(button("Save changes").disabled).toBe(true);
    expect(button("Save changes").getAttribute("aria-busy")).toBe("true");
    expect(button("Cancel").disabled).toBe(false);
  });

  // Bug: Escape or Cancel leaves the modal open, or a closed state still renders one.
  it("escapeAndCancelClose", () => {
    const { onClose } = renderModals({ kind: "create" });
    fireEvent.click(button("Cancel"));
    expect(onClose).toHaveBeenCalledTimes(1);
    dialog().dispatchEvent(new Event("cancel", { cancelable: true }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  // Bug: a closed modal stays mounted as an open dialog over the page.
  it("aNullStateRendersNoDialog", () => {
    renderModals(null);
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
