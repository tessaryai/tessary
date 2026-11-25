// SPDX-License-Identifier: Apache-2.0
/*
 * One row of the issue list, board P1. Expected values are the board's. The bugs worth catching: a loss drawn in grey
 * or a "no clear loss" drawn in red, a row with no curve that shows an empty chart in place of its reason, and a row
 * that opens the wrong page.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { EndUserIssueRow } from "../../api/types";
import { currentLocation, renderRoute } from "../../test/render";
import { ISSUE_LIST } from "../../test/usersFixtures";
import { IssueRow } from "./IssueRow";

const byRef = (ref: string) => ISSUE_LIST.issues.find((r) => r.case_ref === ref)!;

function draw(row: EndUserIssueRow) {
  renderRoute(<IssueRow rows={[row]} />, {
    route: "/orgs/o/projects/p/users",
    path: "users",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
  return screen.getByRole("link");
}

const RED = "var(--color-error)";
const GREY = "var(--color-fg-secondary)";

afterEach(cleanup);

describe("IssueRow", () => {
  // IL2. Bug: the gap label of a claimed loss loses its red, a row with no claim turns red, or a row with a curve but
  // no claim ("always present") drops its curve.
  it("lossRow_isRed_othersGrey", () => {
    const loss = draw(byRef("C-118"));
    expect(within(loss).getByText("about 23 lost (11 to 35)").getAttribute("fill")).toBe(RED);
    cleanup();

    const noClear = draw(byRef("C-121"));
    expect(within(noClear).getByText("no clear loss").getAttribute("fill")).toBe(GREY);
    cleanup();

    const always = draw(byRef("C-123"));
    expect(within(always).getByText("Frustration")).toBeTruthy();
    expect(within(always).getByText("always present · cannot compare").getAttribute("fill")).toBe(GREY);
    expect(
      within(always).getByRole("img", {
        name: "C-123, still active 8 weeks after the hit: hit user IDs 82%, similar user IDs not yet hit 86%; always present · cannot compare",
      }),
    ).toBeTruthy();
  });

  // IL3. Bug: an issue with no curve draws an empty chart, prints nothing, or loses its count of user IDs hit.
  it("rowWithoutCurve_showsOneLineReason", () => {
    const early = draw(byRef("C-125"));
    expect(within(early).getByText("Too early to measure: hit less than 18 days ago")).toBeTruthy();
    expect(within(early).getByText("27")).toBeTruthy();
    expect(within(early).queryByRole("img")).toBeNull();
    cleanup();

    const few = draw(byRef("C-127"));
    expect(within(few).getByText("Too few user IDs to compare: 17 of the 30 needed")).toBeTruthy();
    expect(within(few).getByText("19")).toBeTruthy();
    expect(within(few).queryByRole("img")).toBeNull();
  });

  // IL6. Bug: the row opens the Triage case, the list itself, or an issue page by an ID the route cannot read.
  it("row_linksToIssuePage", () => {
    const link = draw(byRef("C-118"));
    expect(within(link).getByText("Agent forgets facts the user shared in earlier chats")).toBeTruthy();
    expect(within(link).getByText("C-118")).toBeTruthy();
    expect(within(link).getByText("Memory recall")).toBeTruthy();
    expect(within(link).getByText("112")).toBeTruthy();
    expect(link.getAttribute("href")).toBe("/orgs/o/projects/p/users/issues/C-118");

    fireEvent.click(link);
    expect(currentLocation()).toBe("/orgs/o/projects/p/users/issues/C-118");
  });
});
