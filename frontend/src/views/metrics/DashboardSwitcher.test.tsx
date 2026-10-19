// SPDX-License-Identifier: Apache-2.0
/*
 * The dashboard pill row. The bugs worth catching: the wrong pill marked current, a viewer offered manager actions,
 * Rename or Delete offered on the built-in dashboard, and a switch away from an unsaved draft.
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DEFAULT_SUMMARY } from "../../test/metricsFixtures";
import { DashboardSwitcher } from "./DashboardSwitcher";
import type { MetricDashboardSummary } from "./wire";

const COST: MetricDashboardSummary = {
  id: "01JCOST", name: "Cost review", description: "", built_in: false, widget_count: 4, updated_at: "2026-10-17T09:00:00Z",
};
const LATENCY: MetricDashboardSummary = { ...COST, id: "01JLAT", name: "Latency watch", widget_count: 2 };
const ALL = [DEFAULT_SUMMARY, COST, LATENCY];

function renderSwitcher(over: { currentId?: string; canManage?: boolean; disabled?: boolean } = {}) {
  const handlers = { onSelect: vi.fn(), onNew: vi.fn(), onRename: vi.fn(), onDuplicate: vi.fn(), onDelete: vi.fn() };
  render(
    <DashboardSwitcher
      dashboards={ALL}
      currentId={over.currentId ?? "01JCOST"}
      canManage={over.canManage ?? true}
      disabled={over.disabled ?? false}
      {...handlers}
    />,
  );
  return handlers;
}

const nav = () => screen.getByRole("navigation", { name: "Dashboards" });
const pill = (name: string) => within(nav()).getByRole("button", { name }) as HTMLButtonElement;
const kebab = () => screen.queryByRole("button", { name: "Dashboard actions" }) as HTMLButtonElement | null;
const items = () => within(screen.getByRole("menu")).getAllByRole("menuitem");

describe("DashboardSwitcher", () => {
  // Bug: the switcher marks the wrong dashboard, or prints widget counts and tags the screens dropped.
  it("listsDashboardsAsPillsAndMarksTheCurrent", () => {
    renderSwitcher();
    const names = within(nav()).getAllByRole("button").map((b) => b.textContent);
    expect(names).toEqual(["Default", "Cost review", "Latency watch", "New dashboard", ""]);
    expect(pill("Cost review").getAttribute("aria-current")).toBe("page");
    expect(pill("Default").getAttribute("aria-current")).toBeNull();
    expect(within(nav()).queryByText("4")).toBeNull();
  });

  // Bug: a pill click selects the wrong dashboard.
  it("selectingAnotherCallsOnSelect", () => {
    const { onSelect, onNew } = renderSwitcher();
    fireEvent.click(pill("Latency watch"));
    expect(onSelect).toHaveBeenCalledWith("01JLAT");
    fireEvent.click(pill("New dashboard"));
    expect(onNew).toHaveBeenCalledTimes(1);
  });

  // Bug: a viewer sees actions the server refuses.
  it("managersSeeNewDashboardAndTheKebabViewersSeeNeither", () => {
    renderSwitcher({ canManage: false });
    expect(within(nav()).queryByRole("button", { name: "New dashboard" })).toBeNull();
    expect(kebab()).toBeNull();
    expect(within(nav()).getAllByRole("button")).toHaveLength(3);
  });

  // Bug: Rename or Delete on the built-in dashboard, which the server refuses with a 409 the UI has no copy for.
  it("theDefaultOffersOnlyDuplicate", () => {
    const { onDuplicate } = renderSwitcher({ currentId: "default" });
    fireEvent.click(kebab()!);
    expect(items().map((i) => i.textContent)).toEqual(["Duplicate"]);
    fireEvent.click(items()[0]);
    expect(onDuplicate).toHaveBeenCalledWith(DEFAULT_SUMMARY);
    expect(screen.queryByRole("menu")).toBeNull();
  });

  // Bug: a menu item acts on another dashboard than the current one.
  it("aUserDashboardOffersRenameDuplicateAndDeleteOnItself", () => {
    const { onRename, onDuplicate, onDelete } = renderSwitcher();
    fireEvent.click(kebab()!);
    expect(kebab()!.getAttribute("aria-expanded")).toBe("true");
    expect(items().map((i) => i.textContent)).toEqual(["Rename", "Duplicate", "Delete"]);
    fireEvent.click(items()[0]);
    fireEvent.click(kebab()!);
    fireEvent.click(items()[1]);
    fireEvent.click(kebab()!);
    fireEvent.click(items()[2]);
    expect([onRename.mock.calls[0][0], onDuplicate.mock.calls[0][0], onDelete.mock.calls[0][0]]).toEqual([COST, COST, COST]);
  });

  // Bug: the menu traps the keyboard, Escape leaves focus nowhere, or tabbing away leaves it open.
  it("theMenuMovesWithTheArrowKeysAndEscapeReturnsToTheKebab", () => {
    renderSwitcher();
    fireEvent.click(kebab()!);
    const [rename, duplicate, del] = items();
    expect(document.activeElement).toBe(rename);
    fireEvent.keyDown(rename, { key: "ArrowDown" });
    expect(document.activeElement).toBe(duplicate);
    fireEvent.keyDown(duplicate, { key: "End" });
    expect(document.activeElement).toBe(del);
    fireEvent.keyDown(del, { key: "ArrowDown" });
    expect(document.activeElement).toBe(rename);
    fireEvent.keyDown(rename, { key: "ArrowUp" });
    expect(document.activeElement).toBe(del);
    fireEvent.keyDown(del, { key: "Home" });
    expect(document.activeElement).toBe(rename);
    fireEvent.keyDown(rename, { key: "a" });
    expect(document.activeElement).toBe(rename);
    fireEvent.keyDown(rename, { key: "Escape" });
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.activeElement).toBe(kebab());

    fireEvent.click(kebab()!);
    fireEvent.click(kebab()!);
    expect(screen.queryByRole("menu")).toBeNull();

    fireEvent.click(kebab()!);
    fireEvent.blur(items()[0], { relatedTarget: pill("Default") });
    expect(screen.queryByRole("menu")).toBeNull();
  });

  // Bug: switching dashboards mid-edit drops the draft without a word.
  it("editModeDisablesEveryOtherPillTheNewPillAndTheKebab", () => {
    renderSwitcher({ disabled: true });
    expect(pill("Cost review").disabled).toBe(false);
    expect([pill("Default").disabled, pill("Latency watch").disabled, pill("New dashboard").disabled]).toEqual([
      true, true, true,
    ]);
    expect(kebab()!.disabled).toBe(true);
  });

  // Bug: the kebab of a dashboard that failed to load acts on nothing.
  it("noKebabWhenTheCurrentDashboardIsNotListed", () => {
    renderSwitcher({ currentId: "01JGONE" });
    expect(kebab()).toBeNull();
    expect(within(nav()).getByRole("button", { name: "New dashboard" })).toBeTruthy();
  });
});
