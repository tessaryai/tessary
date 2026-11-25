// SPDX-License-Identifier: Apache-2.0
/*
 * The drawer of board P3 on its own: rows, links and paging. The bugs worth catching: a user ID that does not open its
 * traces, or opens them with an unencoded query; a row whose ID text is gone drawn blank or as a dead link; a "more"
 * control that reloads page one or stays after the last page; and the loss colour on a state that is not "stopped".
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type EndUserSilentId, type EndUserSilentIds } from "../../api/types";
import { api } from "../../test/api";
import { pending, renderRoute } from "../../test/render";
import { C118_SILENT_IDS } from "../../test/usersFixtures";
import { SilentIdsRail } from "./SilentIdsRail";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const PROJECT = "/orgs/o/projects/p";

function open() {
  return renderRoute(<SilentIdsRail caseRef="C-118" verdict="evidence_of_loss" silentNow={46} onClose={() => {}} />, {
    route: `${PROJECT}/users/issues/C-118?silent=1`,
    path: "users/issues/:caseId",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
}

const id = (over: Partial<EndUserSilentId>): EndUserSilentId => ({
  id_text: "usr_Pq3w8N",
  weeks_since_hit: 3,
  days_silent: 16,
  stage: "silent",
  grade: null,
  ...over,
});

const lastPage = (ids: EndUserSilentId[]): EndUserSilentIds => ({
  verdict: "evidence_of_loss",
  ids,
  more: 0,
  more_weeks_low: null,
  more_weeks_high: null,
  next_cursor: null,
});

const rail = () => screen.getByRole("dialog", { name: "Hit user IDs that are silent now" });

afterEach(cleanup);

describe("SilentIdsRail", () => {
  // IP6. Bug: a user ID with text does not open its traces, opens them with an unencoded query, or a row with no text
  // is drawn blank or as a link to nothing.
  it("idRow_linksToTraces_whenTextExists", async () => {
    api.getEndUserSilentIds.mockResolvedValue(
      lastPage([id({ id_text: "usr_7Hq2kD" }), id({ id_text: "ana+1@mail.test" }), id({ id_text: null })]),
    );
    open();

    const link = await within(rail()).findByRole("link", { name: "usr_7Hq2kD" });
    expect(link.getAttribute("href")).toBe(`${PROJECT}/traces?q=usr_7Hq2kD`);
    expect(within(rail()).getByRole("link", { name: "ana+1@mail.test" }).getAttribute("href")).toBe(
      `${PROJECT}/traces?q=ana%2B1%40mail.test`,
    );
    expect(within(rail()).getByText("ID text no longer kept").closest("a")).toBeNull();
    expect(within(rail()).getAllByRole("link")).toHaveLength(2);
  });

  // Bug: "more" reloads page one, adds no rows, or stays after the last page.
  it("more_loadsTheNextCursor_untilTheLastPage", async () => {
    api.getEndUserSilentIds.mockImplementation((_ref: string, cursor: string | null) =>
      Promise.resolve(
        cursor === "c-2" ? lastPage([id({ id_text: "usr_Nn5r2C", weeks_since_hit: 4, days_silent: 17 })]) : C118_SILENT_IDS,
      ),
    );
    open();

    fireEvent.click(await within(rail()).findByRole("button", { name: "+ 38 more, hit 1 to 4 weeks ago" }));

    expect(await within(rail()).findByText("usr_Nn5r2C")).toBeTruthy();
    expect(within(rail()).getByText("usr_7Hq2kD")).toBeTruthy();
    expect(api.getEndUserSilentIds).toHaveBeenLastCalledWith("C-118", "c-2");
    expect(within(rail()).queryByRole("button", { name: /more/ })).toBeNull();
  });

  // Bug: the loss colour marks a state other than "stopped".
  it("state_isRedOnlyForStopped", async () => {
    api.getEndUserSilentIds.mockResolvedValue(C118_SILENT_IDS);
    open();

    await within(rail()).findByText("usr_7Hq2kD");
    for (const cell of within(rail()).getAllByText("stopped")) expect(cell.className).toContain("text-error");
    for (const cell of within(rail()).getAllByText("probably stopped")) expect(cell.className).not.toContain("text-error");
    expect(within(rail()).getByText("silent, not judged").className).not.toContain("text-error");
  });

  // Bug: a read in flight or a failed read leaves the drawer with no rows and no word why.
  it("loadingAndError", async () => {
    api.getEndUserSilentIds.mockReturnValue(pending());
    open();
    expect(within(rail()).queryByRole("table")).toBeNull();
    expect(within(rail()).getByText(/One user's stop is not proof/)).toBeTruthy();
    cleanup();

    api.getEndUserSilentIds.mockRejectedValue(new ApiError(500, { code: "END_USER.READ_FAILED", message: "no read" }));
    open();
    expect(await within(rail()).findByRole("alert")).toBeTruthy();
    expect(within(rail()).getByText(/no read/)).toBeTruthy();
  });
});
