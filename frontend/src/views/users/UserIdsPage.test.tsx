// SPDX-License-Identifier: Apache-2.0
/*
 * The ID list of board P5. Expected values are the board's: the tabs "Active · 1,014", "Silent, not judged · 47" and
 * "Probably stopped · 125", nine named rows with "+ 116 more" under them, and C-118 in the Hit by column. The bugs
 * worth catching: a tab that loses its count or its place in the URL, a Hit by column that names issues the server did
 * not name or does not open them, a row with no ID text drawn blank, a held row worded as a plain state, a "more"
 * control that reloads page one or counts wrong, and a stop list shown while the stop count is withheld.
 */
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type EndUserIdPage, type EndUserIdRow } from "../../api/types";
import { api } from "../../test/api";
import { currentLocation, pending, renderRoute } from "../../test/render";
import { overview, PAGE_STATES, STOP_STAGES, STOPPED_IDS } from "../../test/usersFixtures";
import { ID_PAGE_SIZE } from "./usersQueries";
import { UserIdsPage } from "./UserIdsPage";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const PROJECT = "/orgs/o/projects/p";
const LIST = `${PROJECT}/users/ids`;

function open(search = "?stage=stopped") {
  return renderRoute(<UserIdsPage />, {
    route: `${LIST}${search}`,
    path: "users/ids",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
}

const row = (over: Partial<EndUserIdRow>): EndUserIdRow => ({
  id_text: "usr_Lm3v9T",
  state: "active",
  substate: null,
  grade: null,
  days_silent: 2,
  p_stop: 0.04,
  hit_by: [],
  ...over,
});

const page = (stage: EndUserIdPage["stage"], ids: EndUserIdRow[], next_cursor: string | null = null): EndUserIdPage => ({
  page_state: "ok",
  stage,
  ids,
  next_cursor,
});

const tabs = () => screen.getByRole("navigation", { name: "Where your user IDs are" });
/** The rows of the table body: the header row and the "more" footer are not user IDs. */
const bodyRows = () => within(within(screen.getByRole("table")).getAllByRole("rowgroup")[1]).getAllByRole("row");

afterEach(cleanup);

describe("UserIdsPage", () => {
  // I1. Bug: a tab drops its count, or switching tabs does not write ?stage= and read that stage's list.
  it("tabs_showCounts_andSwitchStage", async () => {
    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockImplementation((stage: string) =>
      Promise.resolve(stage === "stopped" ? STOPPED_IDS : page("silent", [row({ substate: "overdue", p_stop: 0.4 })])),
    );
    open();

    const nav = await screen.findByRole("navigation", { name: "Where your user IDs are" });
    const active = within(nav).getByRole("link", { name: "Active · 1,014" });
    const silent = within(nav).getByRole("link", { name: "Silent, not judged · 47" });
    const stopped = within(nav).getByRole("link", { name: "Probably stopped · 125" });
    expect(active.getAttribute("href")).toBe(`${LIST}?stage=active`);
    expect(silent.getAttribute("href")).toBe(`${LIST}?stage=silent`);
    expect(stopped.getAttribute("aria-current")).toBe("page");
    expect(screen.getByRole("heading", { level: 1, name: "Probably stopped" })).toBeTruthy();
    expect(screen.getByText("125 user IDs named, about 141 expected.")).toBeTruthy();
    expect(await screen.findByText("usr_7Hq2kD")).toBeTruthy();
    expect(api.getEndUserIds).toHaveBeenCalledWith("stopped", null, ID_PAGE_SIZE);

    fireEvent.click(silent);

    expect(currentLocation()).toBe(`${LIST}?stage=silent`);
    expect(await screen.findByText("usr_Lm3v9T")).toBeTruthy();
    expect(api.getEndUserIds).toHaveBeenLastCalledWith("silent", null, ID_PAGE_SIZE);
    expect(within(tabs()).getByRole("link", { name: "Silent, not judged · 47" }).getAttribute("aria-current")).toBe(
      "page",
    );
    expect(screen.getByRole("heading", { level: 1, name: "Silent, not judged" })).toBeTruthy();
    expect(screen.getByText("47 user IDs are quieter than usual but not named as stopped.")).toBeTruthy();
  });

  // I2. Bug: the Hit by column adds issues the server did not name, or names them without a link to the issue page.
  it("hitBy_showsOnlyServerNamedIssues", async () => {
    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockResolvedValue(STOPPED_IDS);
    open();

    await screen.findByText("usr_7Hq2kD");
    const rows = bodyRows();
    const hitLinks = rows.map((r) => within(r).queryAllByRole("link").map((a) => a.textContent));
    expect(hitLinks).toEqual([["C-118"], ["C-118"], [], [], ["C-118"], [], ["C-118"], [], []]);
    expect(within(rows[0]).getByRole("link", { name: "C-118" }).getAttribute("href")).toBe(
      `${PROJECT}/users/issues/C-118`,
    );
    expect(screen.getByText('"Hit by" names only issues with evidence of loss or few accounts.')).toBeTruthy();
  });

  // I3. Bug: a row whose ID text is no longer kept shows an empty cell.
  it("rowWithoutIdText_showsPlaceholder", async () => {
    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockResolvedValue(
      page("stopped", [row({ id_text: null, state: "stopped", grade: "stopped", days_silent: 52, p_stop: 0.94 })]),
    );
    open();

    const [only] = await waitFor(() => bodyRows());
    const cells = within(only).getAllByRole("cell");
    expect(cells[0].textContent).toBe("ID text no longer kept");
    expect(cells[1].textContent).toBe("stopped");
    expect(cells[2].textContent).toBe("silent 52 days; user IDs like this stayed away in 94% of past cases");
  });

  // Bug: the cell's default text-fg sits next to the caller's colour and wins in the CSS order, so "stopped" is not red.
  it("stoppedState_isRed_withoutTheDefaultCellColour", async () => {
    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockResolvedValue(
      page("stopped", [row({ id_text: null, state: "stopped", grade: "stopped", days_silent: 52, p_stop: 0.94 })]),
    );
    open();

    const [only] = await waitFor(() => bodyRows());
    const [id, state, why] = within(only).getAllByRole("cell");
    expect([...state.classList].filter((c) => c.startsWith("text-"))).toEqual(["text-error"]);
    expect([...id.classList].filter((c) => c.startsWith("text-"))).toEqual(["text-muted"]);
    expect([...why.classList].filter((c) => c.startsWith("text-"))).toEqual(["text-fg-secondary"]);
  });

  // I4. Bug: a user ID held in a shared break reads as a plain silent or active user ID.
  it("heldRow_reads", async () => {
    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockResolvedValue(page("silent", [row({ substate: "held", days_silent: 9, p_stop: 0.3 })]));
    open("?stage=silent");

    const [only] = await waitFor(() => bodyRows());
    const cells = within(only).getAllByRole("cell");
    expect(cells[1].textContent).toBe("held as a possible break");
    expect(cells[2].textContent).toBe("silent 9 days, in the same week as many other user IDs");
  });

  // I5. Bug: "more" counts the wrong rest, reloads page one, or stays after the last page.
  it("more_loadsNextPage", async () => {
    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds
      .mockResolvedValueOnce(STOPPED_IDS)
      .mockResolvedValueOnce(
        page("stopped", [row({ id_text: "usr_Zt5w2N", state: "stopped", grade: "probably_stopped", days_silent: 22, p_stop: 0.65 })]),
      );
    open();

    const more = await screen.findByRole("button", { name: "+ 116 more" });
    expect(bodyRows()).toHaveLength(9);

    fireEvent.click(more);

    expect(await screen.findByText("usr_Zt5w2N")).toBeTruthy();
    expect(api.getEndUserIds).toHaveBeenLastCalledWith("stopped", "c-2", ID_PAGE_SIZE);
    expect(bodyRows()).toHaveLength(10);
    expect(screen.queryByRole("button", { name: /more/ })).toBeNull();
  });

  // I6. Bug: the stop list shows while the stage withholds its count, or the stopped tab still claims a count.
  it("stoppedTab_isHidden_whenStopStageIsWithheld", async () => {
    const { state, numbers } = STOP_STAGES.not_judged_yet;
    api.getEndUsersOverview.mockResolvedValue(overview({ stop_stage_state: state, stop_stage_numbers: numbers }));
    open();

    const nav = await screen.findByRole("navigation", { name: "Where your user IDs are" });
    expect(within(nav).getByRole("link", { name: "Probably stopped" }).getAttribute("aria-current")).toBe("page");
    expect(within(nav).getByRole("link", { name: "Active · 1,014" })).toBeTruthy();
    expect(
      screen.getByText("The stop odds are not yet checked against this app's past (about 14 more days needed)."),
    ).toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
    expect(api.getEndUserIds).not.toHaveBeenCalled();
  });

  // Bug: "cannot be named yet" still names user IDs, so hiding its list would hide named stops.
  it("keeps the stop list when the named count shows with the cannot-be-named notice", async () => {
    const { state, numbers } = STOP_STAGES.cannot_be_named_yet;
    api.getEndUsersOverview.mockResolvedValue(
      overview({ stop_stage_state: state, stop_stage_numbers: numbers, stages: { ...overview().stages!, stopped_named: 58 } }),
    );
    api.getEndUserIds.mockResolvedValue(STOPPED_IDS);
    open();

    expect(await screen.findByText("usr_7Hq2kD")).toBeTruthy();
    expect(within(tabs()).getByRole("link", { name: "Probably stopped · 58" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "+ 49 more" })).toBeTruthy();
  });

  // Bug: a missing or unknown ?stage= opens no tab; the funnel's "See user IDs" link carries none.
  it.each(["", "?stage=gone"])("opens the Active tab for %j", async (search) => {
    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockResolvedValue(page("active", [row({ state: "new", days_silent: 0 })]));
    open(search);

    expect(await screen.findByText("usr_Lm3v9T")).toBeTruthy();
    expect(api.getEndUserIds).toHaveBeenCalledWith("active", null, ID_PAGE_SIZE);
    expect(within(tabs()).getByRole("link", { name: "Active · 1,014" }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByText("1,014 user IDs are active, including 61 new.")).toBeTruthy();
    expect(bodyRows()[0].textContent).toContain("new; active today");
    expect(screen.getByRole("link", { name: "Users" }).getAttribute("href")).toBe(`${PROJECT}/users`);
  });

  // Bug: a page that shows a state in place of numbers still draws tabs and an empty list instead of the reason.
  it("shows the page state and no list when the page holds its numbers back", async () => {
    api.getEndUsersOverview.mockResolvedValue(PAGE_STATES.tooEarly);
    open();

    expect(await screen.findByRole("region", { name: "Too early" })).toBeTruthy();
    expect(screen.queryByRole("navigation", { name: "Where your user IDs are" })).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
    expect(api.getEndUserIds).not.toHaveBeenCalled();
  });

  // Bug: a read in flight shows nothing, or a failed read shows nothing instead of its code.
  it("shows loading, then the error code of a failed read", async () => {
    api.getEndUsersOverview.mockReturnValue(pending());
    open();
    expect(screen.getByText("Loading…")).toBeTruthy();
    cleanup();

    api.getEndUsersOverview.mockRejectedValue(new ApiError(404, { code: "PROJECT.NOT_FOUND", message: "No project." }));
    open();
    expect((await screen.findByRole("alert")).textContent).toBe("PROJECT.NOT_FOUND: No project.");
    cleanup();

    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockReturnValue(pending());
    open();
    expect(await screen.findByRole("status", { name: "Loading table" })).toBeTruthy();
    cleanup();

    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIds.mockRejectedValue(new ApiError(400, { code: "COMMON.INVALID_PARAMETER", message: "limit" }));
    open();
    expect((await screen.findByRole("alert")).textContent).toBe("COMMON.INVALID_PARAMETER: limit");
  });
});
