// SPDX-License-Identifier: Apache-2.0
/*
 * The issue table of board P1. Expected values are the board's: six rows from seven cases, because C-119 and C-126 fire
 * together and show as one. The bugs worth catching: a list that re-sorts what the server ranked, a group shown as two
 * rows or with one reference, and an empty list that shows a bare table.
 */
import { cleanup, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { EndUserIssueRow } from "../../api/types";
import { renderRoute } from "../../test/render";
import { ISSUE_LIST } from "../../test/usersFixtures";
import { IssueList } from "./IssueList";

function draw(issues: EndUserIssueRow[]) {
  renderRoute(<IssueList issues={issues} />, {
    route: "/orgs/o/projects/p/users",
    path: "users",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
  return screen.getByRole("region", { name: "Issues, by users lost" });
}

const ISSUE = "/orgs/o/projects/p/users/issues/";
const hrefs = (list: HTMLElement) => within(list).getAllByRole("link").map((a) => a.getAttribute("href"));

afterEach(cleanup);

describe("IssueList", () => {
  // IL1. Bug: the list re-sorts the rows (by rank, by count or by reference) instead of keeping the server's order, or
  // loses its header or a column head.
  it("rows_keepServerOrder", () => {
    const list = draw(ISSUE_LIST.issues);

    expect(within(list).getByRole("heading", { level: 2 }).textContent).toBe("Issues, by users lost");
    expect(within(list).getByText("Issue")).toBeTruthy();
    expect(within(list).getByText("User IDs hit")).toBeTruthy();
    expect(within(list).getByText("Still active, weeks 1 to 8 after the hit")).toBeTruthy();
    expect(within(list).getByText("hit")).toBeTruthy();
    expect(within(list).getByText("similar user IDs not yet hit")).toBeTruthy();
    expect(hrefs(list)).toEqual(["C-118", "C-121", "C-119", "C-123", "C-125", "C-127"].map((r) => ISSUE + r));
    cleanup();

    const [c118, c121, ...rest] = ISSUE_LIST.issues;
    const swapped = draw([c121, c118, ...rest]);
    expect(hrefs(swapped)).toEqual(["C-121", "C-118", "C-119", "C-123", "C-125", "C-127"].map((r) => ISSUE + r));
  });

  // IL4. Bug: two findings that fire together show as two rows (counting their user IDs twice), or the one row names
  // only the first reference.
  it("groupRow_showsBothReferences", () => {
    const list = draw(ISSUE_LIST.issues);

    const group = within(list).getByText("C-119 + C-126").closest("a")!;
    expect(within(group).getByText("2 findings that fire together, shown as one")).toBeTruthy();
    expect(
      within(group).getByText(
        "Agent drops its persona and answers like a generic assistant · Agent switches language mid-chat",
      ),
    ).toBeTruthy();
    expect(within(group).getByText("51")).toBeTruthy();
    expect(group.getAttribute("href")).toBe(`${ISSUE}C-119`);
    expect(within(list).queryByText("Agent switches language mid-chat")).toBeNull();
    expect(within(list).getAllByText("51")).toHaveLength(1);
  });

  // IL5. Bug: a project with no cases shows an empty table with no word on why.
  it("noCases_showsEmptyLine", () => {
    const list = draw([]);

    expect(within(list).getByRole("heading", { level: 2 }).textContent).toBe("Issues, by users lost");
    expect(
      within(list).getByText("No cases yet. An issue shows here when a finding becomes a case in Triage."),
    ).toBeTruthy();
    expect(within(list).queryByRole("link")).toBeNull();
    expect(within(list).queryByText("User IDs hit")).toBeNull();
  });
});
