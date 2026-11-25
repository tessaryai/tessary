// SPDX-License-Identifier: Apache-2.0
/*
 * The chip on a Triage case row, board P0. The bugs worth catching: a count printed without its unit, a loss mark on a
 * case with no evidence of loss, a chip that prints "null" or "0" for a case that shifts a measure for all traffic, a
 * tooltip that does not say the page has no numbers, and a chip that opens the wrong page.
 */
import { cleanup, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { EndUserHitCount } from "../../api/types";
import { HIT_COUNTS } from "../../test/usersFixtures";
import { renderRoute } from "../../test/render";
import { UserIdsHitChip } from "./UserIdsHitChip";

const BASE = "/orgs/acme/projects/default";
const C118 = HIT_COUNTS.cases[0];
const C125 = HIT_COUNTS.cases[1];

const draw = (count: EndUserHitCount | undefined) => renderRoute(<UserIdsHitChip count={count} basePath={BASE} />);

afterEach(cleanup);

describe("UserIdsHitChip", () => {
  // T1. Bug: the count shows bare, or without "user IDs", so a reader takes it for people.
  it("chip_showsCount: 112 gives 112 user IDs hit", () => {
    draw(C118);

    expect(screen.getByText("112 user IDs hit")).toBeTruthy();
  });

  // T2. Bug: a loss mark on a case the Users page did not find evidence of loss for, or a loss shown in grey.
  it("lossMark_onlyWithEvidenceOfLoss: about 23 lost (11 to 35) in red for C-118, none for the others", () => {
    draw(C118);
    const loss = screen.getByText("about 23 lost (11 to 35)");
    expect(loss.className).toContain("text-error");
    expect(screen.getByText("112 user IDs hit").className).not.toContain("text-error");
    cleanup();

    for (const other of HIT_COUNTS.cases.slice(1)) {
      draw(other);
      expect(screen.getByRole("link").textContent).toBe(`${other.user_ids_hit} user IDs hit`);
      expect(screen.queryByText(/lost/)).toBeNull();
      cleanup();
    }
  });

  // T3. Bug: a case that shifts a measure for all traffic, or one the read did not list, prints a count.
  it.each<[string, EndUserHitCount | undefined]>([
    ["a null count", { ...C125, user_ids_hit: null }],
    ["not applicable", { ...C125, count_kind: "not_applicable", user_ids_hit: null }],
    ["not applicable with a count", { ...C125, count_kind: "not_applicable" }],
    ["no entry for the case", undefined],
  ])("noChip_whenCountIsNullOrNotApplicable: %s", (_, count) => {
    draw(count);

    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.queryByText(/user IDs hit/)).toBeNull();
  });

  // Bug: the chip opens the Users page for a case whose loss is on its own issue page, or the reverse.
  it("opens the issue page when it shows a loss and the Users page otherwise", () => {
    draw(C118);
    expect(screen.getByRole("link").getAttribute("href")).toBe(`${BASE}/users/issues/01JCASEC118`);
    cleanup();

    draw(C125);
    expect(screen.getByRole("link").getAttribute("href")).toBe(`${BASE}/users`);
  });

  // Bug: the tooltip claims a checked count while identity is not checked, or hides that the Users page has no numbers.
  it.each<[string, EndUserHitCount, string]>([
    ["covered", C125, "Counts user IDs, not people. Opens the Users view."],
    ["id values", { ...C125, count_kind: "id_values" }, "user ID values; identity not checked yet"],
    [
      "covered on a refused page",
      { ...C125, refusal: "too_early" },
      "Counts user IDs, not people. The Users page has no numbers now: too early.",
    ],
  ])("tooltip: %s", (_, count, tooltip) => {
    draw(count);

    expect(screen.getByRole("link").getAttribute("title")).toBe(tooltip);
  });
});
