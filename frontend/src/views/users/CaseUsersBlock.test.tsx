// SPDX-License-Identifier: Apache-2.0
/*
 * The count line of the Users block on a case page, board P4. Expected values are the board's: "112 user IDs hit since
 * Sep 22. 9 of 1,406 flagged traces had no user ID." The bugs worth catching: a count without its unit or its date, the
 * count not standing out from the rest of the line, a case that shifts a measure for all traffic printing "null user
 * IDs hit", a read for another case, and a failed read that shows nothing.
 */
import { cleanup, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import { api } from "../../test/api";
import { pending, renderRoute } from "../../test/render";
import { C118_CASE_BLOCK } from "../../test/usersFixtures";
import { CaseUsersBlock } from "./CaseUsersBlock";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const draw = () => renderRoute(<CaseUsersBlock caseId="C-118" />);

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
