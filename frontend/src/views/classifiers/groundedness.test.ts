// SPDX-License-Identifier: Apache-2.0
/*
 * The setup links move to the running version's tag, and the status words read a 12-hour clock: time
 * alone today, with the date on any other day, and in production the later of a score and a run.
 */
import { describe, expect, it, vi } from "vitest";
import type { GroundednessStatus } from "../../api/types";
import {
  GROUNDEDNESS_AWS_MD,
  atRef,
  clockTime,
  notScoringLabel,
  readSetupFlag,
  restartPrompt,
  rowState,
} from "./groundedness";

function at(day: number, hour: number, minute: number): Date {
  return new Date(2026, 8, day, hour, minute);
}

function status(overrides: Partial<GroundednessStatus>): GroundednessStatus {
  return {
    state: "not_scoring",
    mode: "dev",
    configured: true,
    available: false,
    reason: "unreachable: ConnectException",
    checked_at: null,
    ever_swept: true,
    last_scored_at: null,
    last_caught_up_at: null,
    setup_ref: "main",
    ...overrides,
  };
}

describe("groundedness", () => {
  it("links the guide at the given ref", () => {
    expect(atRef(GROUNDEDNESS_AWS_MD, "v1.3.0")).toBe(
      "https://github.com/tessaryai/tessary/blob/v1.3.0/classifiers/groundedness/setup/groundedness-setup-aws.md",
    );
    expect(restartPrompt("production", "v1.3.0")).toBe(
      "Restart the Groundedness model on AWS by following " +
        "https://github.com/tessaryai/tessary/blob/v1.3.0/classifiers/groundedness/setup/groundedness-setup-aws.md#restart",
    );
  });

  it("reads a 12-hour clock, with the date when it isn't today", () => {
    const now = at(23, 16, 0);
    expect(clockTime(at(23, 14, 2).toISOString(), now)).toBe("2:02 PM");
    expect(clockTime(at(22, 9, 30).toISOString(), now)).toBe("Sep 22, 9:30 AM");
  });

  it("says since the later of a score and, in production, a run", () => {
    const now = at(23, 16, 0);
    const scored = at(23, 14, 1).toISOString();
    const ran = at(23, 14, 2).toISOString();
    expect(notScoringLabel(status({ last_scored_at: scored, last_caught_up_at: ran }), now)).toBe(
      "No scores since 2:01 PM",
    );
    expect(
      notScoringLabel(status({ mode: "production", last_scored_at: scored, last_caught_up_at: ran }), now),
    ).toBe("No scores since 2:02 PM");
    expect(notScoringLabel(status({}), now)).toBe("Not scoring");
  });

  it("reads a disabled row that was never set up as not set up", () => {
    expect(rowState(status({ state: "off", ever_swept: false }))).toBe("not_set_up");
    expect(rowState(status({ state: "off", ever_swept: true }))).toBe("off");
    expect(rowState(status({ state: "off", ever_swept: false, available: true }))).toBe("off");
  });
});

describe("readSetupFlag", () => {
  it("reads no setup under way when storage refuses", () => {
    const spy = vi.spyOn(window.localStorage, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });

    expect(readSetupFlag("acme", "default")).toBe(false);
    spy.mockRestore();
  });
});
