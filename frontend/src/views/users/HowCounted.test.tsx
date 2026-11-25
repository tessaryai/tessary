// SPDX-License-Identifier: Apache-2.0
/*
 * The "How is this counted?" disclosure of board P1: a toggle in the page header and a panel under it. Expected
 * strings are the board texts, with the set-aside line as the words contract words it. The bugs worth catching: a
 * toggle that does not open or close the panel or keeps its label, a trust line without the "IDs, not people"
 * caveat, and a refused page that prints a count it does not have.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import type { EndUserOverview } from "../../api/types";
import { overview, PAGE_STATES } from "../../test/usersFixtures";
import { HowCounted, HowCountedToggle } from "./HowCounted";

function Header({ o }: { o: EndUserOverview }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <HowCountedToggle open={open} onToggle={() => setOpen(!open)} />
      {open && <HowCounted overview={o} />}
    </>
  );
}

const TRUST =
  "Probably stopped is judged against this app's own record of returns, not a fixed number of days. User IDs named as stopped at past dates stayed away in 93% of cases (this checks IDs, not people).";

afterEach(cleanup);

describe("HowCounted", () => {
  // P6. Bug: the toggle does not open or close the panel, keeps its label, or the trust line loses its caveat.
  it("howCounted_toggles_andShowsTrustLine_withIdsNotPeopleCaveat", () => {
    render(<Header o={overview()} />);

    const toggle = screen.getByRole("button", { name: "How is this counted?" });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByText(TRUST)).toBeNull();

    fireEvent.click(toggle);

    expect(toggle.textContent).toBe("Hide how this is counted");
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    const panel = screen.getByRole("region", { name: "How is this counted?" });
    expect(toggle.getAttribute("aria-controls")).toBe(panel.id);
    expect(screen.getByText(TRUST)).toBeTruthy();
    expect(
      screen.getByText(
        "1,186 user IDs are counted; 42 are set aside: 31 that run like a service, 11 that look like keys. History since Jul 14.",
      ),
    ).toBeTruthy();
    expect(
      screen.getByText(
        "An issue's users lost are stops above similar user IDs not yet hit. Counts of different issues overlap.",
      ),
    ).toBeTruthy();

    fireEvent.click(toggle);

    expect(toggle.textContent).toBe("How is this counted?");
    expect(screen.queryByText(TRUST)).toBeNull();
  });

  // Bug: a refused page prints "null user IDs are counted" or a past-check share it does not have.
  it("leaves out the counts a refused page does not have", () => {
    render(<HowCounted overview={PAGE_STATES.tooEarly} />);

    expect(
      screen.getByText(
        "Probably stopped is judged against this app's own record of returns, not a fixed number of days.",
      ),
    ).toBeTruthy();
    expect(screen.queryByText(/are counted/)).toBeNull();
  });
});
