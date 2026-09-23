// SPDX-License-Identifier: Apache-2.0
/*
 * GroundednessTurnOffModal: production warns that the AWS instance keeps running, dev doesn't, and
 * Turn off switches the classifier off.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { GroundednessMode } from "../../api/types";
import { GroundednessTurnOffModal } from "./GroundednessTurnOffModal";

const setClassifierEnabled = vi.fn(async () => ({}));

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({ api: { base: "/api/orgs/acme/projects/default", setClassifierEnabled } }),
  };
});

beforeAll(() => {
  // jsdom implements <dialog> but not showModal/close.
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
});

afterEach(() => {
  cleanup();
  setClassifierEnabled.mockClear();
});

function renderModal(mode: GroundednessMode | undefined, onClose = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <GroundednessTurnOffModal classifierId="clf-g" mode={mode} onClose={onClose} />
    </QueryClientProvider>,
  );
  return onClose;
}

describe("GroundednessTurnOffModal", () => {
  it("warns that the AWS instance keeps running in production", () => {
    renderModal("production");

    screen.getByText("Findings and detections stay.");
    screen.getByText("The AWS instance keeps running");
    screen.getByText("Delete the groundedness-model stack in CloudFormation to stop charges.");
  });

  it("has no AWS warning in dev", () => {
    renderModal("dev");

    screen.getByText("Findings and detections stay.");
    expect(screen.queryByText("The AWS instance keeps running")).toBeNull();
  });

  it("switches the classifier off and closes", async () => {
    const onClose = renderModal("dev");

    fireEvent.click(screen.getByRole("button", { name: "Turn off" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", false);
  });
});
