// SPDX-License-Identifier: Apache-2.0
/*
 * ClassifierResetModal: a reset spends money and deletes history, so it takes two steps, and the second
 * one sends nothing until the classifier's name is typed back exactly.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { renderRoute } from "../../test/render";
import { chartReadsStale, seedChartReads } from "../../test/chartReads";
import { classifier } from "../../test/fixtures";
import { ClassifierResetModal } from "./ClassifierResetModal";
import { api } from "../../test/api";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

beforeEach(() => api.resetClassifier.mockImplementation(async (id: string) => ({ id })));

describe("ClassifierResetModal", () => {
  it("sends the reset only after Continue and the exact name typed back", async () => {
    const onClose = vi.fn();
    renderRoute(<ClassifierResetModal classifier={classifier({})} onClose={onClose} />);

    screen.getByText(/calls your provider again for every user turn/);
    fireEvent.click(screen.getByRole("button", { name: "Continue" }));

    const reset = screen.getByRole("button", { name: "Reset" });
    const input = screen.getByLabelText("Type the classifier name to confirm the reset");
    fireEvent.change(input, { target: { value: "frustration" } });
    expect(reset).toHaveProperty("disabled", true);
    fireEvent.click(reset);
    expect(api.resetClassifier).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "Frustration" } });
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(api.resetClassifier.mock.calls).toEqual([["clf-1"]]);
  });

  // Bug: after a reset the Classifiers cards still draw the deleted detections and the old baseline.
  it("marks the Classifiers charts and menu stale once the reset lands", async () => {
    const onClose = vi.fn();
    const { queryClient } = renderRoute(<ClassifierResetModal classifier={classifier({})} onClose={onClose} />);
    seedChartReads(queryClient);

    fireEvent.click(screen.getByRole("button", { name: "Continue" }));
    fireEvent.change(screen.getByLabelText("Type the classifier name to confirm the reset"), {
      target: { value: "Frustration" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(chartReadsStale(queryClient)).toEqual([true, true]);
  });

  it("warns a free classifier about triage cost but not about a provider", () => {
    renderRoute(
      <ClassifierResetModal
        classifier={classifier({ id: "clf-2", classifier_key: "secret_leak", name: "Secret Leak", detector: "secret_leak" })}
        onClose={vi.fn()}
      />,
    );

    screen.getByText(/Each new finding can start a triage run/);
    expect(screen.queryByText(/calls your provider/)).toBeNull();
  });
});
