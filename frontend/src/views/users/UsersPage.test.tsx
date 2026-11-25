// SPDX-License-Identifier: Apache-2.0
/*
 * The Users page of board P1, composed: the header with "Updated daily" and the "How is this counted?" toggle, the
 * banners, the funnel card with "See user IDs", the limit lines and the issue list, in that order. On a refused page
 * (P6) the state notice takes the funnel's place and the issue list still shows the user IDs hit. The bugs worth
 * catching: parts drawn out of the board's order, a refused page that draws a funnel it has no numbers for or hides
 * the issue list, and one failed read that blanks the whole page.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, type EndUserIssueList } from "../../api/types";
import { api } from "../../test/api";
import { pending, renderRoute } from "../../test/render";
import { AS_OF, BANNERS, ISSUE_LIST, issueNumbers, overview, PAGE_STATES } from "../../test/usersFixtures";
import { UsersPage } from "./UsersPage";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

function open() {
  return renderRoute(<UsersPage />, {
    route: "/orgs/o/projects/p/users",
    path: "users",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
}

/** `a` comes before `b` in the document. */
const before = (a: Node, b: Node) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

const HELD_BANNER =
  "From Nov 10, 31% of regular user IDs went silent in the same week (usual: 9%). 212 user IDs are held as a possible break until Dec 8: between 40 and 118 of them have stopped. A break or a loss; not yet known.";
const LIMIT = "Weak activity basis: traffic that may be automatic counts as activity.";

/** Two cases on a refused page: the server sends the user IDs hit and no number, no curve. */
const REFUSED_ISSUES: EndUserIssueList = {
  page_state: "too_early",
  as_of: AS_OF,
  issues: [
    { ...ISSUE_LIST.issues[0], issue: issueNumbers({ verdict: "page_refused", shown: false, user_ids_hit: 112 }) },
    { ...ISSUE_LIST.issues[1], issue: issueNumbers({ verdict: "page_refused", shown: false, user_ids_hit: 74 }) },
  ],
};

afterEach(cleanup);

describe("UsersPage", () => {
  // Bug: a part of the page is missing or drawn out of the board's order (banners under the funnel, the limit lines
  // under the issue list, the how-counted panel away from the header).
  it("usersPage_order", async () => {
    api.getEndUsersOverview.mockResolvedValue(
      overview({
        banners: [BANNERS.sharedSilence],
        limits: [{ code: "lenient_basis", ids: 0, never_cut: false, numbers: {} }],
      }),
    );
    api.getEndUserIssues.mockResolvedValue(ISSUE_LIST);
    open();

    const title = screen.getByRole("heading", { level: 1, name: "Users" });
    const updated = screen.getByText("Updated daily");
    const toggle = screen.getByRole("button", { name: "How is this counted?" });
    const banner = await screen.findByText(HELD_BANNER);
    const funnel = screen.getByRole("region", { name: "Where your user IDs are" });
    const limit = screen.getByText(LIMIT);
    const issues = await screen.findByRole("region", { name: "Issues, by users lost" });

    expect(within(funnel).getByRole("link", { name: "See user IDs →" }).getAttribute("href")).toBe(
      "/orgs/o/projects/p/users/ids",
    );
    expect(within(issues).getByText("about 23 lost (11 to 35)")).toBeTruthy();
    expect(before(title, updated)).toBe(true);
    expect(before(updated, toggle)).toBe(true);
    expect(before(toggle, banner)).toBe(true);
    expect(before(banner, funnel)).toBe(true);
    expect(before(funnel, limit)).toBe(true);
    expect(before(limit, issues)).toBe(true);

    expect(screen.queryByRole("region", { name: "How is this counted?" })).toBeNull();
    fireEvent.click(toggle);
    const panel = screen.getByRole("region", { name: "How is this counted?" });
    expect(within(panel).getByText(/^1,186 user IDs are counted/)).toBeTruthy();
    expect(before(toggle, panel)).toBe(true);
    expect(before(panel, banner)).toBe(true);
    expect(screen.getByRole("button", { name: "Hide how this is counted" }).getAttribute("aria-expanded")).toBe("true");
  });

  // Bug: a refused page draws the funnel with numbers it does not have, drops its reason, or hides the issue list
  // whose user IDs hit still show.
  it("refusedPage_showsReason_andIssueListWithUserIdsHitOnly", async () => {
    api.getEndUsersOverview.mockResolvedValue(PAGE_STATES.tooEarly);
    api.getEndUserIssues.mockResolvedValue(REFUSED_ISSUES);
    open();

    const notice = await screen.findByRole("region", { name: "Too early" });
    expect(
      within(notice).getByText(
        "14 days of user ID history (21 needed) and 16 user IDs (20 needed). The page fills in on its own.",
      ),
    ).toBeTruthy();
    const issues = await screen.findByRole("region", { name: "Issues, by users lost" });
    expect(screen.queryByRole("region", { name: "Where your user IDs are" })).toBeNull();
    expect(before(notice, issues)).toBe(true);

    const rows = within(issues).getAllByRole("link");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("112")).toBeTruthy();
    expect(within(rows[1]).getByText("74")).toBeTruthy();
    expect(within(issues).getAllByText("No number while the page shows a state")).toHaveLength(2);
    expect(within(issues).queryByRole("img")).toBeNull();
  });

  // Bug: one failed read blanks the whole page, so a broken issue read also hides the funnel, or the other way round.
  it("eachReadFailure_staysInItsOwnPart", async () => {
    api.getEndUsersOverview.mockRejectedValue(new ApiError(500, { code: "end_users.read_failed", message: "boom" }));
    api.getEndUserIssues.mockResolvedValue(ISSUE_LIST);
    open();

    expect(await screen.findByText(/end_users\.read_failed/)).toBeTruthy();
    expect(await screen.findByRole("region", { name: "Issues, by users lost" })).toBeTruthy();
    expect(screen.getByRole("heading", { level: 1, name: "Users" })).toBeTruthy();
    cleanup();

    api.getEndUsersOverview.mockResolvedValue(overview());
    api.getEndUserIssues.mockRejectedValue(new ApiError(500, { code: "end_users.issues_failed", message: "boom" }));
    open();

    expect(await screen.findByText(/end_users\.issues_failed/)).toBeTruthy();
    expect(await screen.findByRole("region", { name: "Where your user IDs are" })).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Issues, by users lost" })).toBeNull();
  });

  // Bug: while a read is in flight the page draws an empty funnel or "No cases yet" in place of a loading state.
  it("loading_showsNoNumbersYet", () => {
    api.getEndUsersOverview.mockReturnValue(pending());
    api.getEndUserIssues.mockReturnValue(pending());
    open();

    expect(screen.getByRole("heading", { level: 1, name: "Users" })).toBeTruthy();
    expect(screen.queryByRole("region", { name: "Where your user IDs are" })).toBeNull();
    expect(screen.queryByText(/No cases yet/)).toBeNull();
    expect(screen.getAllByRole("status").length).toBeGreaterThan(0);
  });
});
