// SPDX-License-Identifier: Apache-2.0
/*
 * The classifiers' shared vocabulary. The bugs worth catching: an age that reads as recent for a date in
 * the future or an unreadable one, a ruling with no verdict given a verdict's words, and a triage run
 * that gave up looking like one still running.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { BehaviorFinding } from "../../api/types";
import { RunTriageButton, ago, triageState } from "./shared";

const NOW = new Date("2026-09-25T12:00:00Z");
const before = (ms: number) => new Date(NOW.getTime() - ms).toISOString();
const MIN = 60_000;

beforeEach(() => {
  vi.useFakeTimers({ now: NOW, toFake: ["Date"] });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("ago", () => {
  it.each([
    [30_000, "just now"],
    [5 * MIN, "5m ago"],
    [3 * 60 * MIN, "3h ago"],
    [2 * 24 * 60 * MIN, "2d ago"],
  ])("reads %i ms as %s", (ms, words) => {
    expect(ago(before(ms))).toBe(words);
  });

  it("dates, rather than ages, a time a month back or one in the future", () => {
    const long = new Date(before(40 * 24 * 60 * MIN)).toLocaleDateString(undefined, {
      month: "long",
      day: "numeric",
      year: "numeric",
    });
    expect(ago(before(40 * 24 * 60 * MIN))).toBe(long);
    expect(ago(before(-5 * MIN))).not.toMatch(/ago|just now/);
  });
});

describe("triageState", () => {
  it("closes a ruling that carries no verdict without claiming one", () => {
    expect(triageState({ triageStatus: "done", triageVerdict: null, humanVerdictAt: null } as BehaviorFinding)).toEqual({
      label: "Closed",
      tone: "closed",
    });
  });
});

describe("RunTriageButton", () => {
  it("says a run gave up, and offers it again rather than looking busy", () => {
    const onAnalyze = vi.fn();
    render(<RunTriageButton finding={{ triageStatus: "failed" } as BehaviorFinding} busy={false} onAnalyze={onAnalyze} />);

    expect(screen.getByText("Triage failed")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Run triage again" }));
    expect(onAnalyze).toHaveBeenCalled();
  });
});
