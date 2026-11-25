// SPDX-License-Identifier: Apache-2.0
/*
 * The Users block on a case page, board P4. Expected values are the board's: "112 user IDs hit since Sep 22. 9 of 1,406
 * flagged traces had no user ID.", then the verdict "Evidence of loss", "about 23 user IDs lost (11 to 35)", the stops
 * sentence, "See users lost" and the small curve ending at 86% and 57%. The bugs worth catching: a count without its
 * unit or its date, the count not standing out from the rest of the line, a case that shifts a measure for all traffic
 * printing "null user IDs hit", a read for another case, a failed read that shows nothing, a verdict part drawn for a
 * case with no issue numbers, and a verdict with no number that still prints a loss, a link or a curve.
 */
import { cleanup, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import { api } from "../../test/api";
import { pending, renderRoute } from "../../test/render";
import { C118_CASE_BLOCK, issueNumbers, NO_DIAGNOSTICS } from "../../test/usersFixtures";
import { CaseUsersBlock } from "./CaseUsersBlock";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const draw = () => renderRoute(<CaseUsersBlock caseId="C-118" />);

/** Mounted where the app mounts the case page, so the link to the issue page resolves as it does in the app. */
const drawOnCasePage = () =>
  renderRoute(<CaseUsersBlock caseId="C-118" />, {
    route: "/orgs/o/projects/p/cases/C-118",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "cases/:caseId",
  });

const STOPS =
  "41 of 96 compared hit user IDs have probably stopped in the 21 days after a hit. 18 were expected from similar user IDs not yet hit.";

afterEach(cleanup);

describe("CaseUsersBlock", () => {
  // CB1. Bug: the count shows bare, without "user IDs", without the day of the first hit, or the flagged traces with
  // no user ID drop out; or the count does not stand out from the rest of the line.
  it("countLine: 112 user IDs hit since Sep 22. 9 of 1,406 flagged traces had no user ID.", async () => {
    api.getEndUserCaseBlock.mockResolvedValue(C118_CASE_BLOCK);
    draw();

    const lead = await screen.findByText("112 user IDs hit");
    expect(lead.tagName).toBe("STRONG");
    expect(lead.parentElement?.textContent).toBe(
      "112 user IDs hit since Sep 22. 9 of 1,406 flagged traces had no user ID.",
    );
    expect(api.getEndUserCaseBlock).toHaveBeenCalledWith("C-118");
  });

  // CB2. Bug: the verdict part misses its badge, its number with the range, its sentence, the link to the issue page or
  // the small curve; or a case with no issue numbers yet (before the first snapshot) still draws a badge, a link or a
  // curve.
  it("verdictPart_onlyWhenShown: Evidence of loss, about 23 user IDs lost (11 to 35), See users lost, 86% and 57%", async () => {
    api.getEndUserCaseBlock.mockResolvedValue(C118_CASE_BLOCK);
    drawOnCasePage();

    expect(await screen.findByText("Evidence of loss")).toBeTruthy();
    expect(screen.getByText("about 23 user IDs lost (11 to 35)")).toBeTruthy();
    expect(screen.getByText(STOPS)).toBeTruthy();
    const link = screen.getByRole("link", { name: "See users lost →" });
    expect(link.getAttribute("href")).toBe("/orgs/o/projects/p/users/issues/C-118");
    const chart = screen.getByRole("img");
    expect(within(chart).getByText("86%")).toBeTruthy();
    expect(within(chart).getByText("57%")).toBeTruthy();
    expect(within(chart).getByText("about 23 lost")).toBeTruthy();

    cleanup();
    api.getEndUserCaseBlock.mockResolvedValue({ ...C118_CASE_BLOCK, count_kind: "id_values", issue: null });
    drawOnCasePage();

    expect(await screen.findByText("112 user IDs hit")).toBeTruthy();
    expect(screen.queryByText("Evidence of loss")).toBeNull();
    expect(screen.queryByText(/lost/)).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
  });

  // CB5. Bug: a verdict with no number prints a loss, the stops sentence, a link to users lost or a curve; or it shows
  // its badge without the one line that says why there is no number.
  it("verdictWithoutNumber_showsItsOneLineReason_andNoChart", async () => {
    api.getEndUserCaseBlock.mockResolvedValue({
      ...C118_CASE_BLOCK,
      issue: issueNumbers({
        verdict: "cannot_measure_yet",
        shown: false,
        user_ids_hit: 112,
        diagnostics: { ...NO_DIAGNOSTICS, t_vis: 18, upper: 30 },
      }),
    });
    drawOnCasePage();

    expect(await screen.findByText("Cannot measure yet")).toBeTruthy();
    expect(screen.getByText("Too early to measure: hit less than 18 days ago")).toBeTruthy();
    expect(screen.getByText("112 user IDs hit")).toBeTruthy();
    expect(screen.queryByText(/lost/)).toBeNull();
    expect(screen.queryByText(/probably stopped/)).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
  });

  // CB3. Bug: a case that shifts a measure for all traffic prints "null user IDs hit" or a count of zero.
  it("metricDriftCase_shows: This issue shifts a measure for all traffic; it has no hit user IDs.", async () => {
    api.getEndUserCaseBlock.mockResolvedValue({
      ...C118_CASE_BLOCK,
      count_kind: "not_applicable",
      user_ids_hit: null,
      since_day: null,
      issue: null,
    });
    draw();

    expect(await screen.findByText("This issue shifts a measure for all traffic; it has no hit user IDs.")).toBeTruthy();
    expect(screen.queryByText(/user IDs hit/)).toBeNull();
    expect(screen.queryByText(/flagged traces/)).toBeNull();
  });

  // CB4, the block's half. Bug: a failed read shows an empty block, or a generic line without the server's code.
  it("a failed read shows the error in the block", async () => {
    api.getEndUserCaseBlock.mockRejectedValue(new ApiError(500, { code: "END_USER.READ_FAILED", message: "no read" }));
    draw();

    expect((await screen.findByRole("alert")).textContent).toContain("END_USER.READ_FAILED");
    expect(screen.queryByText(/user IDs hit/)).toBeNull();
  });

  // Bug: a read still in flight shows an empty block, so it reads as a case with nothing to say.
  it("says it is loading while the read is in flight", () => {
    api.getEndUserCaseBlock.mockReturnValue(pending());
    draw();

    expect(screen.getByText("Loading…")).toBeTruthy();
  });
});
