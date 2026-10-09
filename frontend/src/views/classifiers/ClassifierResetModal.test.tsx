// SPDX-License-Identifier: Apache-2.0
/*
 * ClassifierResetModal: a reset spends money and deletes history, so it takes two steps, and the second
 * one sends nothing until the classifier's name is typed back exactly.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { renderRoute } from "../../test/render";
import { chartReadsStale, seedChartReads } from "../../test/chartReads";
import type { Classifier } from "../../api/types";
import { ClassifierResetModal } from "./ClassifierResetModal";

const resetClassifier = vi.fn(async (id: string) => ({ id }));

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", resetClassifier },
    }),
  };
});

afterEach(() => resetClassifier.mockClear());

function classifier(overrides: Partial<Classifier>): Classifier {
  return {
    id: "clf-1",
    classifier_key: "frustration",
    name: "Frustration",
    description: "Frustration the agent caused.",
    detector: "frustration",
    config_json: null,
    built_in: true,
    version: 9,
    enabled: true,
    mode: "tracking",
    created_at: "2026-09-01T00:00:00Z",
    updated_at: "2026-09-01T00:00:00Z",
    readiness: null,
    call_site_ids: null,
    ...overrides,
  };
}

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
    expect(resetClassifier).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "Frustration" } });
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(resetClassifier.mock.calls).toEqual([["clf-1"]]);
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
