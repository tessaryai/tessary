// SPDX-License-Identifier: Apache-2.0
/*
 * The banners above the funnel (board P6) and the limit lines under it. Expected strings are the board texts and the
 * words contract's limit copy. The bugs worth catching: banners re-sorted or merged so the order the server ranked is
 * lost, the held banner without its "not yet known" caution, a banner with raw numbers, and an empty box on a page
 * with no facts to state.
 */
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { BANNERS } from "../../test/usersFixtures";
import { LimitLines, UsersBanners } from "./UsersBanners";

afterEach(cleanup);

describe("UsersBanners", () => {
  // P5. Bug: the banners come out in a fixed client order (or merged into one), not the server's order, or the held
  // banner loses its caution.
  it("banners_oneForEachFact_inServerOrder", () => {
    render(
      <UsersBanners banners={[BANNERS.sharedSilence, BANNERS.idLoss, BANNERS.groupOrKeyIds, BANNERS.seasonLevel]} />,
    );

    const notes = screen.getAllByRole("note").map((n) => n.textContent);
    expect(notes).toEqual([
      "From Nov 10, 31% of regular user IDs went silent in the same week (usual: 9%). 212 user IDs are held as a possible break until Dec 8: between 40 and 118 of them have stopped. A break or a loss; not yet known.",
      "Since Nov 3, 38% of the traffic on companion.reply arrives without a user ID (before: 2%). About 60 user IDs that look stopped are probably still active. Stop counts after this date are too high until the user ID is sent again.",
      "18 user IDs look like groups or keys (9% of ID-days). Counts on this page are user IDs. One ID can hold many people, and people who leave inside an ID that stays active cannot be seen.",
      "User IDs that no issue touched use the app at 72% of their usual rate since about Nov 8. This can be a season or a problem that no finding covers.",
    ]);
    expect(notes[0]).toContain("212 user IDs are held as a possible break until Dec 8");
    expect(notes[0]).toContain("A break or a loss; not yet known.");
  });

  // Bug: a page with no banner facts still draws an empty banner area.
  it("draws nothing with no banners", () => {
    const { container } = render(<UsersBanners banners={[]} />);
    expect(container.childElementCount).toBe(0);
  });
});

describe("LimitLines", () => {
  // Bug: the limit lines lose the server's order or print raw numbers.
  it("lists each limit line in server order", () => {
    render(
      <LimitLines
        limits={[
          { code: "few_covered_ids", ids: 64, never_cut: false, numbers: { ids: 64 } },
          { code: "lenient_basis", ids: 0, never_cut: false, numbers: {} },
        ]}
      />,
    );

    expect(screen.getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "This app has 64 user IDs. An issue needs about 30 comparable hit user IDs, and verdicts on small apps can change from week to week.",
      "Weak activity basis: traffic that may be automatic counts as activity.",
    ]);
  });

  // Bug: a page with no limits still draws an empty list.
  it("draws nothing with no limits", () => {
    const { container } = render(<LimitLines limits={[]} />);
    expect(container.childElementCount).toBe(0);
  });
});
