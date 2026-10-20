// SPDX-License-Identifier: Apache-2.0
/*
 * The classifiers' shared vocabulary. The bugs worth catching: an age that reads as recent for a date in
 * the future or an unreadable one, a ruling with no verdict given a verdict's words, and a triage run
 * that gave up looking like one still running.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ProjectApi } from "../../api/client";
import type { BehaviorFinding } from "../../api/types";
import { GROUNDEDNESS_FINDING_DETAIL } from "../../test/groundednessFixtures";
import { RunBar } from "../components/RunBar";
import { RunTriageButton, TriageStateText, ago, triageBar, triageState } from "./shared";

const NOW = new Date("2026-09-25T12:00:00Z");
const before = (ms: number) => new Date(NOW.getTime() - ms).toISOString();
const MIN = 60_000;

beforeEach(() => {
  vi.useFakeTimers({ now: NOW, toFake: ["Date"] });
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

it("triageState: closes a ruling that carries no verdict without claiming one", () => {
  expect(triageState({ triageStatus: "done", triageVerdict: null, humanVerdictAt: null } as BehaviorFinding)).toEqual({
    label: "Closed",
    tone: "closed",
  });
});

const BASE = "/orgs/acme/projects/default";

type Progress = NonNullable<BehaviorFinding["triageProgress"]>;
type Run = NonNullable<BehaviorFinding["triageRun"]>;

const progress = (over: Partial<Progress> = {}): Progress => ({
  attempts: 1, maxAttempts: 5, startedAt: null, heldBy: null, queuedAt: null, retryAt: null, revivableAt: null,
  cooldownSeconds: 1800, ...over,
});

const run = (over: Partial<Run> = {}): Run => ({
  id: "run-1", attempt: 1, startedAt: before(134_000), endedAt: null, stepCount: 7,
  doing: "Reading flagged answers 51 to 58", outcome: null, stopCode: null, ...over,
});

const finding = (over: Partial<BehaviorFinding> = {}): BehaviorFinding => ({
  ...GROUNDEDNESS_FINDING_DETAIL.finding, ...over,
});

const stepsApi = () => ({ getTriageSteps: vi.fn().mockResolvedValue({ run_id: "run-1", step_count: 0, steps: [] }) });

const bar = (f: BehaviorFinding, canProviders = true, api = stepsApi()) =>
  triageBar(f, BASE, canProviders, NOW.getTime(), api as unknown as ProjectApi);

describe("RunTriageButton", () => {
  // Bug: the button said "Retrying…" while the design names every live run "Triaging…", and a stopped run kept a
  // red label and words beside the button that now belong to the bar under the header.
  it.each([
    ["pending", "Run triage", false],
    ["queued", "Queued…", true],
    ["in_flight", "Triaging…", true],
    ["retrying", "Triaging…", true],
    ["blocked", "Run triage again", false],
    ["failed", "Run triage again", false],
  ] as const)(
    "the button says Queued, Triaging and Run triage again and is off while a run waits (%s)",
    (triageStatus, label, off) => {
      const onAnalyze = vi.fn();
      render(<RunTriageButton finding={finding({ triageStatus, triageProgress: progress() })} busy={false} onAnalyze={onAnalyze} />);

      const button = screen.getByRole("button", { name: label }) as HTMLButtonElement;
      expect(button.disabled).toBe(off);
      expect(screen.queryByText(/Triage failed|Triage blocked|attempt/i)).toBeNull();
      fireEvent.click(button);
      expect(onAnalyze).toHaveBeenCalledTimes(off ? 0 : 1);
    },
  );

  // Bug: a second press while the first is in flight sent the run twice.
  it("is off while a press is in flight", () => {
    render(<RunTriageButton finding={finding()} busy onAnalyze={() => {}} />);

    expect((screen.getByRole("button", { name: "Run triage" }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe("triageBar", () => {
  // Bug: a finding never sent to triage, or one already ruled, grew a bar under its header.
  it("is absent before triage is asked for and once it rules", () => {
    expect(bar(finding({ triageStatus: "pending" }))).toBeNull();
    expect(bar(finding({ triageStatus: "done", triageVerdict: "positive", triageRun: run({ endedAt: before(1000) }) }))).toBeNull();
  });

  // Bug: a queued run read the same as a running one, with no time since it was asked for.
  it("a queued run's bar says it waits for a worker and how long it has waited", () => {
    expect(bar(finding({ triageStatus: "queued", triageProgress: progress({ attempts: 0, queuedAt: before(6_000) }) })))
      .toEqual({ kind: "Triage", state: "queued", line: "Queued. It starts when a worker is free.", meta: "0:06" });
  });

  // Bug: a running bar lost the words of the step it is on, its step number, or its time.
  it("a running run's bar says what the agent is doing, on which step and for how long, and reads live steps", async () => {
    const api = stepsApi();
    const props = bar(
      finding({ triageStatus: "in_flight", triageProgress: progress({ startedAt: before(134_000) }), triageRun: run() }),
      true,
      api,
    )!;

    expect(props).toMatchObject({
      kind: "Triage", state: "running", line: "Reading flagged answers 51 to 58…", meta: "step 7 · 2m 14s",
    });
    expect(props.steps?.live).toBe(true);
    await props.steps?.load(3);
    expect(api.getTriageSteps).toHaveBeenCalledWith("fnd-1", 3);
  });

  // Bug: between two steps, or before the worker opened its run, the bar showed the last run's words or nothing.
  it("a running run between steps says it is thinking, and before its run opens offers no steps", () => {
    expect(bar(finding({
      triageStatus: "in_flight", triageProgress: progress({ startedAt: before(14_000) }), triageRun: run({ doing: null }),
    }))).toMatchObject({ line: "Thinking…", meta: "step 7 · 14s" });

    const opening = bar(finding({
      triageStatus: "in_flight", triageProgress: progress({ startedAt: before(3_000) }),
      triageRun: run({ endedAt: before(60_000), outcome: "retrying" }),
    }))!;
    expect(opening).toMatchObject({ line: "Thinking…", meta: "3s" });
    expect(opening.steps).toBeUndefined();
    expect(bar(finding({ triageStatus: "in_flight", triageProgress: null, triageRun: null }))).toMatchObject({
      state: "running", line: "Thinking…", meta: undefined,
    });
  });

  // Bug: a retrying run read as "Triaging" with nothing saying an attempt had ended, or which one comes next.
  it("a retrying run's bar says which attempt ended and which runs next", () => {
    const props = bar(finding({
      triageStatus: "retrying", triageProgress: progress({ attempts: 1, retryAt: before(-30_000) }),
      triageRun: run({ endedAt: before(1000), outcome: "retrying", doing: null }),
    }))!;

    expect(props).toMatchObject({
      state: "running", line: "Attempt 1 of 5 ended with no ruling. Trying again…", meta: "attempt 2 of 5",
    });
    expect(props.steps?.live).toBe(false);
  });

  // Bug: a run held on a rejected key said only "blocked", with no way to the page that fixes it.
  it("a blocked run's bar names the fix and links to Providers", () => {
    const blocked = finding({
      triageStatus: "blocked", triageProgress: progress({ heldBy: "CLASSIFIER.TRIAGE_PROVIDER_REJECTED" }),
      triageRun: run({ endedAt: before(1000), outcome: "held", stopCode: "CLASSIFIER.TRIAGE_PROVIDER_REJECTED" }),
    });
    const props = bar(blocked)!;
    render(<MemoryRouter><RunBar {...props} /></MemoryRouter>);

    expect(screen.getByRole("img", { name: "Stopped" })).toBeTruthy();
    expect(screen.getByText("Stopped: the provider rejected the stored key. Fix the key, then run triage again.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open Providers" }).getAttribute("href")).toBe(`${BASE}/settings/providers`);
    expect(screen.queryByRole("button", { name: /steps/ })).toBeNull();
    expect(bar(blocked, false)?.fix).toBeUndefined();
  });

  // Bug: a run that gave up said only that it failed, not how often it was tried or when it can run again.
  it("a failed run's bar says when it can run again", () => {
    const props = bar(finding({
      triageStatus: "failed", triageProgress: progress({ attempts: 5, revivableAt: before(-60_000), cooldownSeconds: 1800 }),
      triageRun: run({ endedAt: before(1000), outcome: "dead" }),
    }))!;

    expect(props).toMatchObject({
      state: "stopped", line: "Stopped after 5 attempts with no ruling. It can run again 30 minutes after it stopped.",
    });
    expect(props.steps?.live).toBe(false);
    expect(bar(finding({
      triageStatus: "failed", triageProgress: progress({ attempts: 1, cooldownSeconds: 60 }), triageRun: null,
    }))).toMatchObject({ line: "Stopped after 1 attempt with no ruling. It can run again 1 minute after it stopped.", steps: undefined });
  });
});

describe("TriageStateText", () => {
  // Bug: the findings list marked a stopped run with an icon beside grey words, where the design reads red text.
  it.each([
    ["pending", "Pending", "text-muted"],
    ["queued", "Queued", "text-muted"],
    ["in_flight", "Triaging", "text-muted"],
    ["retrying", "Retrying", "text-muted"],
    ["blocked", "Triage blocked", "text-error"],
    ["failed", "Triage failed", "text-error"],
  ] as const)("the list text is red only for blocked and failed (%s)", (triageStatus, label, tone) => {
    const { container } = render(<TriageStateText finding={finding({ triageStatus })} />);

    expect(screen.getByText(label).className).toContain(tone);
    expect(container.querySelector("svg")).toBeNull();
  });

  // Bug: a positive ruling, the one that pages a person, read as quietly as a pending one.
  it("a positive ruling reads in full colour", () => {
    render(<TriageStateText finding={finding({ triageStatus: "done", triageVerdict: "positive" })} />);

    expect(screen.getByText("Positive").className).toContain("text-fg");
  });
});
