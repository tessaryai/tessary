// SPDX-License-Identifier: Apache-2.0
/*
 * The funnel card of board P1. Expected values are the board's: Active 1,014 "including 61 new", Silent, not judged
 * 47 "quiet longer than usual", Probably stopped 125 "about 141 expected". The bugs worth catching: a stage that shows
 * the wrong count or caption, a stage that does not open its own tab of the ID list, and a funnel picture that still
 * claims a stop count the stage withholds.
 */
import { cleanup, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { EndUserStageCounts, EndUserStopStageState } from "../../api/types";
import { overview, STOP_STAGES } from "../../test/usersFixtures";
import { renderRoute } from "../../test/render";
import { FunnelCard } from "./FunnelCard";

function draw(stages: EndUserStageCounts, state: EndUserStopStageState, numbers: Record<string, number>) {
  renderRoute(<FunnelCard stages={stages} stopState={state} stopNumbers={numbers} />, {
    route: "/orgs/o/projects/p/users",
    path: "users",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
  });
  return screen.getByRole("region", { name: "Where your user IDs are" });
}

const P1 = overview();
const LIST = "/orgs/o/projects/p/users/ids";

afterEach(cleanup);

describe("FunnelCard", () => {
  // P4. Bug: a stage shows the wrong count or caption, or opens the wrong tab (or none) of the ID list.
  it("funnel_showsThreeStages_withCaptions_andEachLinksToItsTab", () => {
    const card = draw(P1.stages!, P1.stop_stage_state!, P1.stop_stage_numbers!);

    const stage = (label: string) => within(card).getByText(label).closest("a")!;
    const active = stage("Active");
    expect(within(active).getByText("1,014")).toBeTruthy();
    expect(within(active).getByText("including 61 new")).toBeTruthy();
    expect(active.getAttribute("href")).toBe(`${LIST}?stage=active`);

    const silent = stage("Silent, not judged");
    expect(within(silent).getByText("47")).toBeTruthy();
    expect(within(silent).getByText("quiet longer than usual")).toBeTruthy();
    expect(silent.getAttribute("href")).toBe(`${LIST}?stage=silent`);

    const stopped = stage("Probably stopped");
    expect(within(stopped).getByText("125")).toBeTruthy();
    expect(within(stopped).getByText("about 141 expected")).toBeTruthy();
    expect(stopped.getAttribute("href")).toBe(`${LIST}?stage=stopped`);

    expect(within(card).getByRole("link", { name: "See user IDs →" }).getAttribute("href")).toBe(LIST);
    expect(
      within(card).getByRole("img", {
        name: "1,014 active user IDs, 47 silent and not judged, 125 probably stopped",
      }),
    ).toBeTruthy();
  });

  // Bug: with the stop count withheld, the picture still draws a stop bar and names a stop count.
  it("leaves the stop count out of the picture while the stage withholds it", () => {
    const card = draw(P1.stages!, STOP_STAGES.not_judged_yet.state, STOP_STAGES.not_judged_yet.numbers);

    const picture = within(card).getByRole("img", { name: "1,014 active user IDs, 47 silent and not judged" });
    expect(picture.querySelector('[data-part="bar-stopped"]')).toBeNull();
    expect(picture.querySelector('[data-part="bar-silent"]')).not.toBeNull();
    expect(within(card).getByText("Probably stopped · not judged yet")).toBeTruthy();
    expect(within(card).queryByText("125")).toBeNull();
  });

  // Bug: bar heights follow the raw counts, so a small stage is a hairline, or the tallest bar is not the largest
  // stage. Heights go by the square root of the share of the largest stage, as on the board (120, 26, 42 of 120).
  it("sizes each bar by the square root of its share of the largest stage", () => {
    const card = draw(P1.stages!, P1.stop_stage_state!, P1.stop_stage_numbers!);
    const picture = within(card).getByRole("img");
    const height = (part: string) =>
      Math.round(Number(picture.querySelector(`[data-part="${part}"]`)!.getAttribute("height")));

    expect([height("bar-active"), height("bar-silent"), height("bar-stopped")]).toEqual([120, 26, 42]);
  });
});
