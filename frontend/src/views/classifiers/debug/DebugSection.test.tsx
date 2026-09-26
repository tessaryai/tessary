// SPDX-License-Identifier: Apache-2.0
/*
 * The classifier rail's internal Debug section. The bugs worth catching: the debug read paid for before
 * anyone opens the section, a family shown another family's section, and a metric baseline's windows
 * printed wrong or an empty one claimed to hold samples.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Classifier, ClassifierDebug } from "../../../api/types";
import { pending, renderRoute } from "../../../test/render";
import DebugSection from "./DebugSection";

const api = vi.hoisted(() => ({ base: "/api/orgs/acme/projects/default", getClassifierDebug: vi.fn() }));

vi.mock("../../../tenant/TenantContext", () => ({ useTenant: () => ({ api }) }));

const SWEEP = {
  status: "running",
  attempts: 2,
  last_error: null,
  cursor_at: "2026-09-25T10:00:00Z",
  cursor_id: null,
  lease_owner: "worker-1",
  lease_expires_at: null,
  updated_at: null,
};
const debug = (over: Partial<ClassifierDebug> = {}) =>
  ({ id: "clf-1", detector: "tool_error", family: "deterministic", metric_baselines: null, sweep: SWEEP, ...over }) as ClassifierDebug;
const sketch = (count: number) => ({ count, grid_id: "g1" });

beforeEach(() => {
  api.getClassifierDebug.mockResolvedValue(debug());
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const sweep = () => within(screen.getByRole("heading", { name: "Sweep" }).closest("section")!);
const openSection = () => {
  const details = screen.getByText("Debug").closest("details")!;
  details.open = true;
  fireEvent(details, new Event("toggle"));
};

describe("DebugSection", () => {
  it("reads nothing until opened, then shows the sweep with a dash for what is unset", async () => {
    renderRoute(<DebugSection classifier={{ id: "clf-1" } as Classifier} />);
    expect(api.getClassifierDebug).not.toHaveBeenCalled();

    openSection();

    await screen.findByRole("heading", { name: "Sweep" });
    expect(api.getClassifierDebug).toHaveBeenCalledWith("clf-1");
    expect(sweep().getByText("running")).toBeTruthy();
    expect(sweep().getByText("worker-1")).toBeTruthy();
    expect(sweep().getAllByText("–")).toHaveLength(3);
    expect(sweep().queryByText("Last error")).toBeNull();
    expect(screen.getByText(/Deterministic classifier/)).toBeTruthy();
    expect(screen.getAllByText("Raw").length).toBeGreaterThan(0);
  });

  it("names a sweep that never ran, and its last error", async () => {
    api.getClassifierDebug.mockResolvedValue(debug({ sweep: { ...SWEEP, status: null, last_error: "timeout" } }));
    renderRoute(<DebugSection classifier={{ id: "clf-1" } as Classifier} />);
    openSection();

    await screen.findByRole("heading", { name: "Sweep" });
    expect(sweep().getByText("never enqueued")).toBeTruthy();
    expect(sweep().getByText("timeout")).toBeTruthy();
  });

  it.each([
    ["encoder", /Encoder classifier/],
    ["decision", /Decision-model classifier/],
    ["metric_drift", /No metric_baseline rows yet/],
  ])("shows the %s family its own section", async (family, text) => {
    api.getClassifierDebug.mockResolvedValue(debug({ family }));
    renderRoute(<DebugSection classifier={{ id: "clf-1" } as Classifier} />);
    openSection();

    expect(await screen.findByText(text)).toBeTruthy();
  });

  it("lists each metric baseline's windows, and says when one is empty", async () => {
    api.getClassifierDebug.mockResolvedValue(
      debug({
        family: "metric_drift",
        metric_baselines: [
          {
            measure: "cost",
            bucket_kind: "call_site",
            bucket_key: "checkout",
            state: "armed",
            w1_floor: 0.139,
            current_count: 42,
            current_opened_at: "2026-09-25T09:00:00Z",
            pinned: sketch(500),
            pinned_at: "2026-09-20T00:00:00Z",
            prev: sketch(480),
            current: null,
          },
          {
            measure: "duration",
            bucket_kind: "call_site",
            bucket_key: "search",
            state: "learning",
            w1_floor: 0.2,
            current_count: 3,
            current_opened_at: null,
            pinned: null,
            pinned_at: null,
            prev: null,
            current: sketch(3),
          },
        ],
      } as Partial<ClassifierDebug>),
    );
    renderRoute(<DebugSection classifier={{ id: "clf-1" } as Classifier} />);
    openSection();

    expect(await screen.findByText("Metric baselines · 2")).toBeTruthy();
    const cost = screen.getByText("cost · checkout").closest("div.rounded-control")!;
    expect(cost.textContent).toContain("w1_floor 0.139 · 42 in current window · opened 2026-09-25T09:00:00Z");
    expect(cost.textContent).toContain("pinned: 500 samples · grid g1 · pinned 2026-09-20T00:00:00Z");
    expect(cost.textContent).toContain("prev: 480 samples · grid g1");
    expect(cost.textContent).toContain("current: empty");
    const duration = screen.getByText("duration · search").closest("div.rounded-control")!;
    expect(duration.textContent).toContain("3 in current window");
    expect(duration.textContent).not.toContain("opened");
    expect(duration.textContent).toContain("pinned: empty");
  });

  it("shows the loading row, then a failed read", async () => {
    api.getClassifierDebug.mockReturnValueOnce(pending());
    renderRoute(<DebugSection classifier={{ id: "clf-1" } as Classifier} />);
    openSection();
    expect((await screen.findAllByRole("status")).length).toBeGreaterThan(0);
    expect(screen.queryByRole("heading", { name: "Sweep" })).toBeNull();
    cleanup();

    api.getClassifierDebug.mockRejectedValue(new Error("debug unavailable"));
    renderRoute(<DebugSection classifier={{ id: "clf-1" } as Classifier} />);
    openSection();
    expect(await screen.findByText("debug unavailable")).toBeTruthy();
  });
});
