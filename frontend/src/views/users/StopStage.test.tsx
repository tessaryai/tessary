// SPDX-License-Identifier: Apache-2.0
/*
 * The Probably stopped stage in the seven states of board P6. Expected strings are the board texts, with the
 * days-needed note of "not judged yet" as the words contract fixed it. The bugs worth catching: a withheld state that
 * prints a count (0, "about undefined") in place of the dash, a reason that does not show, the expected count shown
 * as the named count, and red on a count that names no user ID.
 */
import { cleanup, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { EndUserStopStageState } from "../../api/types";
import { overview, STOP_STAGES } from "../../test/usersFixtures";
import { renderRoute } from "../../test/render";
import { StopStage } from "./StopStage";

function draw(state: EndUserStopStageState, numbers: Record<string, number>) {
  renderRoute(<StopStage state={state} numbers={numbers} />, {
    route: "/orgs/o/projects/p/users",
    path: "users",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
  return screen.getByRole("link");
}

const dash = (link: HTMLElement) => link.querySelector('[data-part="dash"]');

afterEach(cleanup);

describe("StopStage", () => {
  // P3. Bug: a withheld state prints a count, or drops its reason, so a reader takes a guess for a measure.
  it.each([
    [
      STOP_STAGES.not_judged_yet,
      "Probably stopped · not judged yet",
      "the past check has 2 of 4 dates",
      "The stop odds are not yet checked against this app's past (about 14 more days needed).",
    ],
    [
      STOP_STAGES.cannot_tell_yet,
      "Probably stopped · cannot tell yet",
      "users here return less often than this history can show",
      "One silence is weak evidence in this app. This stage waits for a longer history.",
    ],
    [
      STOP_STAGES.history_too_short,
      "Probably stopped · history too short",
      "90 days are kept; 120 are needed",
      "Stop counts and issue numbers need 120 days of user ID history.",
    ],
    [
      STOP_STAGES.mixed_id_formats,
      "Probably stopped · mixed ID formats",
      "one-day user IDs and account user IDs may share one format",
      "41% of user IDs are seen on one day only; some are guests, not lost users.",
    ],
  ])("eachStopStageState: $state shows a dash and its reason", ({ state, numbers }, label, caption, note) => {
    const link = draw(state, numbers);

    expect(within(link).getByText(label)).toBeTruthy();
    expect(dash(link)).not.toBeNull();
    expect(link.textContent).not.toMatch(/\d+ expected|about|undefined|null/);
    expect(within(link).getByText(caption)).toBeTruthy();
    expect(screen.getByText(note)).toBeTruthy();
  });

  it("eachStopStageState: expected only gives about 141 expected and none named", () => {
    const link = draw(STOP_STAGES.expected_only.state, STOP_STAGES.expected_only.numbers);

    expect(within(link).getByText("Probably stopped · expected only")).toBeTruthy();
    expect(dash(link)).toBeNull();
    const value = within(link).getByText("about 141");
    expect(value.className).not.toContain("text-error");
    expect(within(link).getByText("expected")).toBeTruthy();
    expect(within(link).getByText("none named")).toBeTruthy();
    expect(
      screen.getByText("Past checks named the right user IDs in only 71% of cases (80% needed), so no list."),
    ).toBeTruthy();
  });

  it("eachStopStageState: cannot be named yet gives 230 expected, 58 named and the notice", () => {
    const link = draw(STOP_STAGES.cannot_be_named_yet.state, STOP_STAGES.cannot_be_named_yet.numbers);

    expect(within(link).getByText("Probably stopped · cannot be named yet")).toBeTruthy();
    expect(within(link).getByText("about 230").className).not.toContain("text-error");
    expect(within(link).getByText("58 named")).toBeTruthy();
    expect(
      screen.getByText(
        "About 230 user IDs have probably stopped. Only 58 can be named, because one silence is weak evidence here (the most it proves: 64%).",
      ),
    ).toBeTruthy();
  });

  it("eachStopStageState: the full state gives 125 in red and about 141 expected", () => {
    const o = overview();
    const link = draw(o.stop_stage_state!, o.stop_stage_numbers!);

    expect(within(link).getByText("Probably stopped")).toBeTruthy();
    expect(within(link).getByText("125").className).toContain("text-error");
    expect(within(link).getByText("about 141 expected")).toBeTruthy();
    expect(dash(link)).toBeNull();
    expect(screen.queryByText(/weak evidence|not yet checked|so no list|history/)).toBeNull();
  });

  // Bug: the stage does not open the list on its own tab.
  it("links to the Probably stopped tab", () => {
    const link = draw(STOP_STAGES.cannot_tell_yet.state, STOP_STAGES.cannot_tell_yet.numbers);
    expect(link.getAttribute("href")).toBe("/orgs/o/projects/p/users/ids?stage=stopped");
  });
});
