// SPDX-License-Identifier: Apache-2.0
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import { numberedSteps, stepsPage, TRIAGE_STEPS } from "../../test/runFixtures";
import { RunBar, RunFold, RunMark, RunSteps } from "./RunBar";
import { QUEUED_LINE, RUN_POLL_MS, stopFix } from "./runWords";

const BASE = "/orgs/acme/projects/default";

function renderIn(ui: ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

/** Lets the steps read that a click or a timer started settle and render. */
async function settle(ms = 0) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

function rows() {
  return within(screen.getByRole("list", { name: "Steps" })).getAllByRole("listitem");
}

afterEach(() => {
  vi.useRealTimers();
});

describe("RunBar", () => {
  // Bug: a queued bar shows a progress line or a steps toggle, as if something were already running.
  it("a queued bar shows the time since it was queued and no progress line", () => {
    renderIn(<RunBar kind="Triage" state="queued" line={QUEUED_LINE} meta="0:06" />);

    expect(screen.getByRole("img", { name: "Queued" })).toBeTruthy();
    expect(screen.getByText("Triage")).toBeTruthy();
    expect(screen.getByText("Queued. It starts when a worker is free.")).toBeTruthy();
    expect(screen.getByText("0:06")).toBeTruthy();
    expect(screen.queryByRole("progressbar")).toBeNull();
    expect(screen.queryByRole("button", { name: /steps/ })).toBeNull();
  });

  // Bug: a running bar loses its live words, its step and time, or the progress line.
  it("a running bar shows the step, the elapsed time and the live words", () => {
    const load = vi.fn();
    renderIn(
      <RunBar kind="Triage" state="running" line="Reading flagged answers 51 to 58…" meta="step 7 · 2m 14s" steps={{ load, live: true }} />,
    );

    expect(screen.getByRole("img", { name: "Running" })).toBeTruthy();
    expect(screen.getByText("Reading flagged answers 51 to 58…")).toBeTruthy();
    expect(screen.getByText("step 7 · 2m 14s")).toBeTruthy();
    expect(screen.getByRole("progressbar", { name: "Triage running" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Show steps" }).getAttribute("aria-expanded")).toBe("false");
  });

  // Bug: a page that reads steps nobody asked for (the paid stub answers 404 to any steps read).
  it("steps load only when opened", async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValue(stepsPage(TRIAGE_STEPS));
    renderIn(<RunBar kind="Triage" state="running" line="Running checks/unsupported_terms.py…" steps={{ load, live: true }} />);
    await settle(RUN_POLL_MS * 2);
    expect(load).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Show steps" }));
    expect(screen.getByRole("status").textContent).toContain("Loading steps…");
    await settle();

    expect(load).toHaveBeenCalledWith(0);
    expect(screen.getByRole("button", { name: "Hide steps" }).getAttribute("aria-expanded")).toBe("true");
    expect(rows().map((row) => row.textContent)).toEqual([
      "✓Read the finding and its numbers0:04",
      "✓Read flagged answers 1 to 50 of 580:19",
      "✓Opened trace 4f1c9a07e2b84d0f0:41",
      "✓Read the reference window1:05",
      "✕Could not run checks/refund_terms.py1:32",
      "✓Wrote checks/unsupported_terms.py2:40",
      "Running checks/unsupported_terms.py…",
    ]);
    expect(rows()[6].getAttribute("aria-current")).toBe("step");

    fireEvent.click(screen.getByRole("button", { name: "Hide steps" }));
    expect(screen.queryByRole("list", { name: "Steps" })).toBeNull();
  });

  // Bug: an open list freezes on the steps it read first, keeps a spinner on a step after the run ended, or
  // keeps reading a run that ended.
  it("an open steps list keeps reading new steps while the run is live and stops when it ends", async () => {
    vi.useFakeTimers();
    const [first, second] = TRIAGE_STEPS;
    const load = vi
      .fn()
      .mockResolvedValueOnce(stepsPage([first, { ...second, ended_ms: null, ok: null }]))
      .mockResolvedValueOnce(stepsPage([second, { ...TRIAGE_STEPS[2], ended_ms: null, ok: null }], { stepCount: 3 }))
      .mockResolvedValueOnce(stepsPage([TRIAGE_STEPS[2]], { stepCount: 3 }));
    const bar = (live: boolean) => (
      <RunBar kind="Triage" state={live ? "running" : "stopped"} line="…" steps={{ load, live }} />
    );
    const { rerender } = renderIn(bar(true));

    fireEvent.click(screen.getByRole("button", { name: "Show steps" }));
    await settle();
    expect(rows().map((row) => row.textContent)).toEqual([
      "✓Read the finding and its numbers0:04",
      "Reading flagged answers 1 to 50…",
    ]);

    await settle(RUN_POLL_MS);
    expect(load).toHaveBeenLastCalledWith(1);
    expect(rows().map((row) => row.textContent)).toEqual([
      "✓Read the finding and its numbers0:04",
      "✓Read flagged answers 1 to 50 of 580:19",
      "Opening trace 4f1c9a07e2b84d0f…",
    ]);

    rerender(<MemoryRouter>{bar(false)}</MemoryRouter>);
    await settle();
    expect(load).toHaveBeenLastCalledWith(2);
    expect(rows()[2].textContent).toContain("✓Opened trace 4f1c9a07e2b84d0f0:41");
    expect(rows()[2].getAttribute("aria-current")).toBeNull();

    await settle(RUN_POLL_MS * 3);
    expect(load).toHaveBeenCalledTimes(3);
  });

  // Bug: a retry's steps are merged into the ended attempt's steps, read from the old attempt's cursor.
  it("a new attempt replaces the steps of the one before", async () => {
    vi.useFakeTimers();
    const load = vi
      .fn()
      .mockResolvedValueOnce(stepsPage(TRIAGE_STEPS.slice(0, 2)))
      .mockResolvedValueOnce(stepsPage([], { runId: "run-2", stepCount: 1 }))
      .mockResolvedValueOnce(stepsPage([{ ...TRIAGE_STEPS[0], ended_ms: null, ok: null }], { runId: "run-2" }));
    renderIn(<RunBar kind="Triage" state="running" line="…" steps={{ load, live: true }} />);
    fireEvent.click(screen.getByRole("button", { name: "Show steps" }));
    await settle();
    expect(rows()).toHaveLength(2);

    await settle(RUN_POLL_MS);
    expect(load.mock.calls).toEqual([[0], [2], [0]]);
    expect(rows().map((row) => row.textContent)).toEqual(["Reading the finding and its numbers…"]);
  });

  // Bug: a failed steps read leaves the panel blank or loading forever.
  it("a failed steps read says why, and a run with no steps yet says so", async () => {
    vi.useFakeTimers();
    const load = vi
      .fn()
      .mockRejectedValueOnce(new ApiError(404, { code: "CLASSIFIER.FINDING_NOT_FOUND", message: "No such finding." }))
      .mockResolvedValueOnce(stepsPage([]));
    renderIn(<RunBar kind="Triage" state="running" line="Thinking…" steps={{ load, live: true }} />);
    fireEvent.click(screen.getByRole("button", { name: "Show steps" }));
    await settle();
    expect(screen.getByRole("alert").textContent).toContain("CLASSIFIER.FINDING_NOT_FOUND: No such finding.");

    await settle(RUN_POLL_MS);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText("No steps yet.")).toBeTruthy();
  });

  // Bug: a stopped bar links to Providers for a person who cannot open it, or loses the link for one who can.
  it("a stopped bar shows the fix link only when the person can open Providers", () => {
    const line = "Stopped: the provider rejected the stored key. Fix the key, then run triage again.";
    const bar = (canProviders: boolean) => (
      <MemoryRouter>
        <RunBar kind="Triage" state="stopped" line={line} fix={stopFix("CLASSIFIER.TRIAGE_PROVIDER_REJECTED", BASE, canProviders)} />
      </MemoryRouter>
    );
    const { rerender } = render(bar(true));

    expect(screen.getByRole("img", { name: "Stopped" })).toBeTruthy();
    expect(screen.getByText(line)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open Providers" }).getAttribute("href")).toBe(`${BASE}/settings/providers`);
    expect(screen.queryByRole("progressbar")).toBeNull();

    rerender(bar(false));
    expect(screen.getByText(line)).toBeTruthy();
    expect(screen.queryByRole("link")).toBeNull();
  });
});

describe("RunSteps", () => {
  // Bug: a long run lists every step, or cuts the oldest ones without saying how many.
  it("more than 30 steps shows the newest and says how many earlier", () => {
    const { rerender } = render(<RunSteps steps={numberedSteps(1, 35)} stepCount={35} />);
    expect(screen.getByText("5 earlier steps")).toBeTruthy();
    expect(rows()).toHaveLength(30);
    expect(rows()[0].textContent).toContain("Read file 6");
    expect(rows()[29].textContent).toContain("Read file 35");

    rerender(<RunSteps steps={numberedSteps(370, 400)} stepCount={401} />);
    expect(screen.getByText("371 earlier steps")).toBeTruthy();
    expect(rows()[0].textContent).toContain("Read file 371");

    rerender(<RunSteps steps={numberedSteps(1, 31)} stepCount={31} />);
    expect(screen.getByText("1 earlier step")).toBeTruthy();

    rerender(<RunSteps steps={numberedSteps(1, 30)} stepCount={30} />);
    expect(screen.queryByText(/earlier step/)).toBeNull();
  });
});

describe("RunFold", () => {
  // Bug: an ended run loses its steps, or the fold cuts them as the live list does.
  it("the fold says how many steps and how long, and opens every step", async () => {
    vi.useFakeTimers();
    const load = vi.fn().mockResolvedValue(stepsPage(numberedSteps(1, 35)));
    render(<RunFold stepCount={35} durationMs={250_000} steps={{ load, live: false }} />);
    const fold = screen.getByRole("button", { name: "Did 35 steps in 4m" });
    expect(fold.getAttribute("aria-expanded")).toBe("false");
    expect(load).not.toHaveBeenCalled();

    fireEvent.click(fold);
    await settle();
    expect(fold.getAttribute("aria-expanded")).toBe("true");
    expect(rows()).toHaveLength(35);
    expect(screen.queryByText(/earlier step/)).toBeNull();

    await settle(RUN_POLL_MS * 2);
    expect(load).toHaveBeenCalledTimes(1);
  });
});

describe("RunMark", () => {
  // Bug: the cases list mark is a bare shape, so a screen reader or a hover cannot tell queued, running and
  // stopped apart.
  it("the case mark names its state for a screen reader and on hover", () => {
    vi.useFakeTimers({ now: Date.parse("2026-10-20T12:00:00Z"), toFake: ["Date"] });
    const { rerender } = render(<RunMark state="running" since="2026-10-20T11:57:00Z" />);
    const mark = screen.getByRole("img", { name: "RCA running" });
    expect(screen.queryByRole("tooltip")).toBeNull();
    fireEvent.mouseEnter(mark);
    expect(screen.getByRole("tooltip").textContent).toContain("RCA running · 3m");
    fireEvent.mouseLeave(mark);
    expect(screen.queryByRole("tooltip")).toBeNull();

    rerender(<RunMark state="queued" since="2026-10-20T11:59:00Z" />);
    fireEvent.mouseEnter(screen.getByRole("img", { name: "RCA queued" }));
    expect(screen.getByRole("tooltip").textContent).toContain("RCA queued · 1m");

    rerender(<RunMark state="stopped" since="2026-10-20T11:00:00Z" />);
    fireEvent.mouseEnter(screen.getByRole("img", { name: "RCA stopped" }));
    expect(screen.getByRole("tooltip").textContent).toBe("RCA stopped");
  });
});
