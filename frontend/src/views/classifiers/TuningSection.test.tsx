// SPDX-License-Identifier: Apache-2.0
/*
 * The drift classifiers' tuning form. The bugs worth catching: a field sent under the wrong name, a
 * cleared field saved as 0 (which the server clamps to its floor), the form not showing what the server
 * actually stored after clamping, and a false-alarm rate made up when there is not enough traffic.
 */
import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Classifier } from "../../api/types";
import { pending, renderRoute } from "../../test/render";
import { TuningSection } from "./TuningSection";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getClassifierTuning: vi.fn(),
  setClassifierTuning: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ api }) }));

const COST = { id: "clf-c", detector: "cost_drift" } as Classifier;
const TUNING = {
  window_target_count: 500,
  window_max_hours: 24,
  min_sample: 100,
  w1_floor: 0.139,
  implied_false_alarm_rate: 0.034,
};

beforeEach(() => {
  api.getClassifierTuning.mockResolvedValue(TUNING);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const field = (label: string) => screen.getByLabelText(label, { exact: false }) as HTMLInputElement;
const save = () => fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe("TuningSection", () => {
  it("seeds the form from the stored tuning and says what it costs in false alarms", async () => {
    renderRoute(<TuningSection classifier={COST} />);

    await waitFor(() => expect(field("Target samples").value).toBe("500"));
    expect(api.getClassifierTuning).toHaveBeenCalledWith("clf-c");
    expect(field("Max hours").value).toBe("24");
    expect(field("Min samples").value).toBe("100");
    expect(field("Smallest shift").value).toBe("0.139");
    expect(screen.getByText(/roughly 3% of comparisons firing/)).toBeTruthy();
  });

  it("prints a rate under 1% to a decimal, and makes none up without the traffic", async () => {
    api.getClassifierTuning.mockResolvedValueOnce({ ...TUNING, implied_false_alarm_rate: 0.0042 });
    renderRoute(<TuningSection classifier={COST} />);
    expect(await screen.findByText(/roughly 0.4% of comparisons/)).toBeTruthy();
    cleanup();

    api.getClassifierTuning.mockResolvedValueOnce({ ...TUNING, implied_false_alarm_rate: null });
    renderRoute(<TuningSection classifier={COST} />);
    expect(await screen.findByText("Not enough traffic yet to say what this costs in false alarms.")).toBeTruthy();
  });

  it("saves every field under its own name, then shows what the server stored", async () => {
    api.setClassifierTuning.mockResolvedValue({ ...TUNING, window_target_count: 100_000, w1_floor: 0.2 });
    renderRoute(<TuningSection classifier={COST} />);
    await waitFor(() => expect(field("Target samples").value).toBe("500"));

    fireEvent.change(field("Target samples"), { target: { value: "250000" } });
    fireEvent.change(field("Max hours"), { target: { value: "48" } });
    fireEvent.change(field("Min samples"), { target: { value: "200" } });
    fireEvent.change(field("Smallest shift"), { target: { value: "0.2" } });
    save();

    await waitFor(() =>
      expect(api.setClassifierTuning).toHaveBeenCalledWith("clf-c", {
        window_target_count: 250000,
        window_max_hours: 48,
        min_sample: 200,
        w1_floor: 0.2,
      }),
    );
    expect(await screen.findByText("Tuning saved")).toBeTruthy();
    expect(field("Target samples").value).toBe("100000");
    expect(field("Max hours").value).toBe("24");
  });

  it("keeps what is being typed when the tuning is re-read underneath it", async () => {
    const { queryClient } = renderRoute(<TuningSection classifier={COST} />);
    await waitFor(() => expect(field("Max hours").value).toBe("24"));
    fireEvent.change(field("Max hours"), { target: { value: "72" } });

    api.getClassifierTuning.mockResolvedValue({ ...TUNING, window_max_hours: 12 });
    await act(() => queryClient.invalidateQueries());

    await waitFor(() =>
      expect(queryClient.getQueryData(["classifier-tuning", api.base, "clf-c"])).toMatchObject({ window_max_hours: 12 }),
    );
    await act(settle);
    expect(field("Max hours").value).toBe("72");
  });

  it("saves nothing while a field is blank", async () => {
    renderRoute(<TuningSection classifier={COST} />);
    await waitFor(() => expect(field("Max hours").value).toBe("24"));

    fireEvent.change(field("Max hours"), { target: { value: "" } });
    expect(screen.queryByRole("alert")).toBeNull();
    save();
    await settle();

    expect(api.setClassifierTuning).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toBe("Fill in Max hours before closing anyway to save.");

    fireEvent.change(field("Max hours"), { target: { value: "48" } });
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("names a refused save, and shows the loading and failed reads", async () => {
    api.setClassifierTuning.mockRejectedValue(new Error("out of range"));
    renderRoute(<TuningSection classifier={COST} />);
    await waitFor(() => expect(field("Max hours").value).toBe("24"));
    save();
    expect(await screen.findByText("out of range")).toBeTruthy();
    cleanup();

    api.getClassifierTuning.mockReturnValueOnce(pending());
    renderRoute(<TuningSection classifier={COST} />);
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    cleanup();

    api.getClassifierTuning.mockRejectedValueOnce(new Error("tuning unavailable"));
    renderRoute(<TuningSection classifier={COST} />);
    expect(await screen.findByText("tuning unavailable")).toBeTruthy();
  });
});
