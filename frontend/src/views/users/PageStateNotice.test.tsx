// SPDX-License-Identifier: Apache-2.0
/*
 * The page states of board P6 that replace the funnel and the issue numbers. Expected strings are the board texts;
 * "No user ID" names the share that fired as the words contract fixed it, and "Collecting history" is the words
 * contract's own copy (no board shows it). The bugs worth catching: a refused page that shows no reason or the wrong
 * one, a partial page that does not name the user IDs it set aside, and a notice on an ok page.
 */
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { EndUserOverview } from "../../api/types";
import { overview, PAGE_STATES, REFUSED } from "../../test/usersFixtures";
import { PageStateNotice } from "./PageStateNotice";

afterEach(cleanup);

describe("PageStateNotice", () => {
  // P1. Bug: a refused page shows the wrong reason, a reason with raw numbers, or none at all.
  it.each<[string, EndUserOverview, string, string]>([
    [
      "no user ID",
      PAGE_STATES.noUserId,
      "No user ID",
      "Only 12% of all traces carry a user ID. Send user.id on every trace, the same for one person across sessions.",
    ],
    [
      "too early",
      PAGE_STATES.tooEarly,
      "Too early",
      "14 days of user ID history (21 needed) and 16 user IDs (20 needed). The page fills in on its own.",
    ],
    [
      "user IDs not reliable (keys)",
      PAGE_STATES.heavyIds,
      "User IDs not reliable: keys",
      "22 user IDs carry the traffic of more than one user (40% of ID-days: 31 sessions a day, 18 hours a day). The other user IDs cannot be taken as single users.",
    ],
    [
      "user IDs not reliable (replaced)",
      PAGE_STATES.idsReplaced,
      "User IDs not reliable: replaced",
      "Of 35 user IDs that ended and had a repeating job, 34 went on under a new user ID. User IDs are replaced, so a silent user ID is not a lost user.",
    ],
    [
      "not computed",
      PAGE_STATES.notComputed,
      "Not computed",
      "This project is over the size budget for the daily job. Coverage and user IDs hit still show.",
    ],
    [
      "collecting",
      overview({ ...REFUSED, page_state: "collecting", page_state_reason: null, as_of: null }),
      "Collecting history",
      "The first daily run has not finished yet. The page fills in on its own.",
    ],
  ])("eachRefusal_showsItsReason: %s", (_name, o, title, text) => {
    render(<PageStateNotice overview={o} />);

    const notice = screen.getByRole("region", { name: title });
    expect(within(notice).getByText(text)).toBeTruthy();
  });

  // P2. Bug: a partial page shows numbers without saying which user IDs are left out of them.
  it("partialCoverage_showsNotice_namingSetAsideIds", () => {
    render(<PageStateNotice overview={PAGE_STATES.partialCoverage} />);

    const notice = screen.getByRole("region", { name: "Partial coverage" });
    expect(
      within(notice).getByText(
        "Numbers use covered user IDs only. Set aside: 31 that run like a service, 11 that look like keys. A banner names each reason.",
      ),
    ).toBeTruthy();
  });

  // Bug: an ok page carries an empty notice box.
  it("shows nothing on an ok page", () => {
    const { container } = render(<PageStateNotice overview={overview()} />);
    expect(container.childElementCount).toBe(0);
  });
});
