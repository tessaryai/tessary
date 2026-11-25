// SPDX-License-Identifier: Apache-2.0
/*
 * The issue page of board P2 and the drawer of board P3, opened by ?silent=1. Expected values are the boards' for C-118:
 * "About 23 user IDs lost (11 to 35)" under the badge "Evidence of loss", 112 user IDs hit of which 96 are compared,
 * 8 late and 8 with no similar user IDs, and 46 hit user IDs silent now, grouped "Hit 8 weeks ago", "Hit 6 to 7 weeks
 * ago" and "Hit 5 weeks ago" with "+ 38 more, hit 1 to 4 weeks ago" under them. The bugs worth catching: a headline
 * that prints a loss for an issue with no number, a drawer offered (or read) for a verdict that makes no claim, a lost
 * "not proof" line, a How-counted panel that drops the late or unmatched counts, and links that miss their pages.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type EndUserIssue } from "../../api/types";
import { api } from "../../test/api";
import { currentLocation, pending, renderRoute } from "../../test/render";
import {
  C118_ISSUE,
  C118_NUMBERS,
  C118_SILENT_IDS,
  C121_NUMBERS,
  C125_NUMBERS,
  issueNumbers,
  NO_DIAGNOSTICS,
  utcDay,
} from "../../test/usersFixtures";
import { IssuePage } from "./IssuePage";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const PROJECT = "/orgs/o/projects/p";
const PAGE = `${PROJECT}/users/issues/C-118`;

function open(search = "") {
  return renderRoute(<IssuePage />, {
    route: `${PAGE}${search}`,
    path: "users/issues/:caseId",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
}

const issue = (over: Partial<EndUserIssue>): EndUserIssue => ({ ...C118_ISSUE, ...over });

const FEW_ACCOUNTS = issue({
  silent_now: 12,
  issue: issueNumbers({
    verdict: "few_accounts",
    user_ids_hit: 40,
    compared: 36,
    lost: 9,
    low: 0,
    high: 17,
    diagnostics: {
      ...NO_DIAGNOSTICS,
      accounts: 2,
      estimated: 9,
      account_lines: [{ account: 7, hit: 22, ids: 30, silent: 9, silent_since_day: utcDay("2026-10-30") }],
    },
  }),
});

const SILENT_BUTTON = "Hit user IDs that are silent now · 46 →";
const NOT_PROOF = "A difference in stops, not proof of cause.";

afterEach(cleanup);

describe("IssuePage", () => {
  // IP1. Bug: the headline or the badge words a different loss or verdict than the issue's numbers.
  it("headline_andVerdictBadge", async () => {
    api.getEndUserIssue.mockResolvedValue(C118_ISSUE);
    open();

    expect(
      await screen.findByRole("heading", { level: 1, name: "Agent forgets facts the user shared in earlier chats" }),
    ).toBeTruthy();
    expect(screen.getByText("Evidence of loss")).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: "About 23 user IDs lost (11 to 35)" })).toBeTruthy();
    expect(
      screen.getByText(
        "41 of 96 compared hit user IDs have probably stopped in the 21 days after a hit. 18 were expected from similar user IDs not yet hit.",
      ),
    ).toBeTruthy();
    expect(screen.getByRole("region", { name: "Retention from the hit day" })).toBeTruthy();
    expect(api.getEndUserIssue).toHaveBeenCalledWith("C-118");
  });

  // IP2. Bug: the drawer is offered for a verdict that makes no claim, or the "not proof" line goes with it.
  it("drawerButton_onlyForEvidenceOfLossOrFewAccounts", async () => {
    api.getEndUserIssue.mockResolvedValue(C118_ISSUE);
    open();
    expect(await screen.findByRole("button", { name: SILENT_BUTTON })).toBeTruthy();
    expect(screen.getByText(NOT_PROOF)).toBeTruthy();
    cleanup();

    api.getEndUserIssue.mockResolvedValue(FEW_ACCOUNTS);
    open();
    expect(await screen.findByRole("button", { name: "Hit user IDs that are silent now · 12 →" })).toBeTruthy();
    expect(screen.getByText("Few accounts")).toBeTruthy();
    expect(
      screen.getByRole("heading", { level: 2, name: "No clear loss; if today's silences last, up to 17" }),
    ).toBeTruthy();
    expect(screen.getByText(NOT_PROOF)).toBeTruthy();
    cleanup();

    api.getEndUserIssue.mockResolvedValue(issue({ silent_now: null, issue: C121_NUMBERS }));
    open();
    expect(await screen.findByText("No clear loss; if today's silences last, up to 14")).toBeTruthy();
    expect(screen.getByText(NOT_PROOF)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /silent now/ })).toBeNull();
  });

  // IP3. Bug: ?silent=1 does not open the drawer, or the drawer loses its columns, note, week groups or "more" line.
  it("rail_groupsByWeeksSinceHit", async () => {
    api.getEndUserIssue.mockResolvedValue(C118_ISSUE);
    api.getEndUserSilentIds.mockResolvedValue(C118_SILENT_IDS);
    open("?silent=1");

    const rail = await screen.findByRole("dialog", { name: "Hit user IDs that are silent now" });
    expect(within(rail).getByText("Hit user IDs that are silent now · 46")).toBeTruthy();
    expect(within(rail).getByText("C-118 · evidence of loss")).toBeTruthy();
    expect(
      within(rail).getByText(
        "One user's stop is not proof the issue caused it. The issue number compares these user IDs with similar user IDs not yet hit.",
      ),
    ).toBeTruthy();
    await within(rail).findByText("usr_7Hq2kD");
    expect(within(rail).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "User ID",
      "Days silent",
      "State",
    ]);
    const groups = within(rail)
      .getAllByRole("rowgroup")
      .slice(1)
      .map((g) => within(g).getAllByRole("row").map((r) => r.textContent));
    expect(groups).toEqual([
      ["Hit 8 weeks ago", "usr_7Hq2kD52stopped", "usr_0Fz81r49stopped"],
      ["Hit 6 to 7 weeks ago", "usr_Mx4a7Q41stopped", "usr_Jt8c2W38stopped", "usr_Rb1n6E33probably stopped"],
      [
        "Hit 5 weeks ago",
        "usr_Gk5p3Z29probably stopped",
        "usr_Sv9d1H24probably stopped",
        "usr_Ye2m8T19silent, not judged",
      ],
    ]);
    expect(within(rail).getByRole("button", { name: "+ 38 more, hit 1 to 4 weeks ago" })).toBeTruthy();
    expect(api.getEndUserSilentIds).toHaveBeenCalledWith("C-118", null);
  });

  // Bug: the drawer button does not write ?silent=1, or closing the drawer leaves it in the URL.
  it("drawerButton_writesSilentParam_andCloseRemovesIt", async () => {
    api.getEndUserIssue.mockResolvedValue(C118_ISSUE);
    api.getEndUserSilentIds.mockResolvedValue(C118_SILENT_IDS);
    open();

    fireEvent.click(await screen.findByRole("button", { name: SILENT_BUTTON }));
    expect(currentLocation()).toBe(`${PAGE}?silent=1`);
    const rail = await screen.findByRole("dialog", { name: "Hit user IDs that are silent now" });

    fireEvent.click(within(rail).getByRole("button", { name: "Close" }));
    expect(currentLocation()).toBe(PAGE);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  // Bug: the silent IDs are read for a verdict that makes no claim, or before anyone opens the drawer.
  it("silentIds_readOnlyWhenDrawerOpensOnAClaim", async () => {
    api.getEndUserIssue.mockResolvedValue(issue({ silent_now: null, issue: C121_NUMBERS }));
    open("?silent=1");
    await screen.findByText(NOT_PROOF);
    expect(screen.queryByRole("dialog")).toBeNull();
    cleanup();

    api.getEndUserIssue.mockResolvedValue(C118_ISSUE);
    open();
    await screen.findByRole("button", { name: SILENT_BUTTON });
    expect(api.getEndUserSilentIds).not.toHaveBeenCalled();
  });

  // IP4. Bug: an issue with no number prints a loss headline or a chart instead of its reason and its user IDs hit.
  it("verdictWithoutNumber_showsReasonAndUserIdsHit_noChart", async () => {
    api.getEndUserIssue.mockResolvedValue(issue({ silent_now: null, issue: C125_NUMBERS }));
    open();

    expect(await screen.findByRole("heading", { level: 2, name: "27 user IDs hit" })).toBeTruthy();
    expect(screen.getByText("Cannot measure yet")).toBeTruthy();
    expect(
      screen.getByText(
        "A stop starts to show about 18 days after the last use here. Half of the hit user IDs were hit less than that ago. If every hit user ID that is silent today stays away: up to 4.",
      ),
    ).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Retention from the hit day" })).toBeNull();
    expect(screen.queryByRole("button", { name: /silent now/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "How is this counted?" })).toBeNull();
    expect(screen.queryByText(/lost/)).toBeNull();
  });

  // IP5. Bug: How counted drops the compared, late or unmatched counts, or the panel does not open and close.
  it("howCounted_showsComparedLateAndUnmatchedCounts", async () => {
    api.getEndUserIssue.mockResolvedValue(C118_ISSUE);
    open();

    const toggle = await screen.findByRole("button", { name: "How is this counted?" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByText(/are compared/)).toBeNull();

    fireEvent.click(toggle);

    const panel = screen.getByRole("region", { name: "How is this counted?" });
    expect(
      within(panel).getByText(
        "112 user IDs hit since Sep 22. 96 are compared: 8 were hit too late to count, and 8 had no similar user IDs to compare with.",
      ),
    ).toBeTruthy();
    expect(
      within(panel).getByText(
        "Each hit user ID is compared, from its hit day, with similar user IDs that were not yet hit on that day.",
      ),
    ).toBeTruthy();
    expect(within(panel).getByText("Two user IDs of one person count as one stop and one new user ID.")).toBeTruthy();
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(toggle.textContent).toBe("Hide how this is counted");

    fireEvent.click(toggle);
    expect(screen.queryByRole("region", { name: "How is this counted?" })).toBeNull();
  });

  // IP6. Bug: the breadcrumb or the "Open case" link misses the Users page or the case page.
  it("breadcrumb_andOpenCaseLink", async () => {
    api.getEndUserIssue.mockResolvedValue(C118_ISSUE);
    open();

    const crumbs = await screen.findByRole("navigation", { name: "Breadcrumb" });
    expect(within(crumbs).getByRole("link", { name: "Users" }).getAttribute("href")).toBe(`${PROJECT}/users`);
    expect(within(crumbs).getByText("C-118")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open case C-118" }).getAttribute("href")).toBe(
      `${PROJECT}/cases/01JCASEC118`,
    );
  });

  // Bug: an issue the snapshot has no row for prints numbers, a badge or the drawer from nothing.
  it("noIssueRow_showsHeaderOnly", async () => {
    api.getEndUserIssue.mockResolvedValue(issue({ page_state: "collecting", as_of: null, silent_now: null, issue: null }));
    open();

    expect(await screen.findByRole("link", { name: "Open case C-118" })).toBeTruthy();
    expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
    expect(screen.queryByText(NOT_PROOF)).toBeNull();
    expect(screen.queryByText("Evidence of loss")).toBeNull();
  });

  // Bug: a failed read shows nothing, or a read in flight shows a page with no data.
  it("loadingAndError", async () => {
    api.getEndUserIssue.mockReturnValue(pending());
    open();
    expect(screen.getByText("Loading…")).toBeTruthy();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    cleanup();

    api.getEndUserIssue.mockRejectedValue(new ApiError(404, { code: "END_USER.CASE_NOT_FOUND", message: "No case C-118." }));
    open();
    expect(await screen.findByText(/No case C-118\./)).toBeTruthy();
  });

  // Bug: the badge marks a verdict that claims no loss in the loss colour.
  it("badge_isRedOnlyForEvidenceOfLoss", async () => {
    api.getEndUserIssue.mockResolvedValue(issue({ issue: C118_NUMBERS }));
    open();
    expect((await screen.findByText("Evidence of loss")).className).toContain("text-error");
    cleanup();

    api.getEndUserIssue.mockResolvedValue(FEW_ACCOUNTS);
    open();
    expect((await screen.findByText("Few accounts")).className).not.toContain("text-error");
  });
});
