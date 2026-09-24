// SPDX-License-Identifier: Apache-2.0
/*
 * GroundednessRestartModal: the prompt links the guide's restart section at the running version's tag,
 * and the modal closes itself once a status poll reads the model scoring again.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { GroundednessStatus } from "../../api/types";
import { GroundednessRestartModal } from "./GroundednessRestartModal";
import { SETUP_POLL_MS } from "./GroundednessEnableModal";

const getGroundednessStatus = vi.fn<(id: string) => Promise<GroundednessStatus>>();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({ api: { base: "/api/orgs/acme/projects/default", getGroundednessStatus } }),
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
  vi.useRealTimers();
  getGroundednessStatus.mockReset();
});

function status(overrides: Partial<GroundednessStatus>): GroundednessStatus {
  return {
    state: "not_scoring",
    mode: "production",
    configured: true,
    available: false,
    reason: "unreachable: ConnectException",
    checked_at: null,
    ever_swept: true,
    last_scored_at: "2026-09-23T14:02:00Z",
    last_caught_up_at: null,
    setup_ref: "v1.3.0",
    ...overrides,
  };
}

function renderModal(onClose = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <GroundednessRestartModal classifierId="clf-g" mode="production" setupRef="v1.3.0" onClose={onClose} />
    </QueryClientProvider>,
  );
  return onClose;
}

/** Moves the fake clock `ms` on and lets the reads and renders it starts settle. */
async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe("GroundednessRestartModal", () => {
  it("copies the restart prompt, linked at the running version's tag", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    getGroundednessStatus.mockResolvedValue(status({}));
    renderModal();

    fireEvent.click(screen.getByRole("button", { name: "Copy prompt" }));

    await waitFor(() => expect(writeText).toHaveBeenCalledOnce());
    expect(writeText).toHaveBeenCalledWith(
      "Restart the Groundedness model on AWS by following " +
        "https://github.com/tessaryai/tessary/blob/v1.3.0/classifiers/groundedness/setup/groundedness-setup-aws.md#restart",
    );
  });

  it("closes itself once a poll reads the model scoring again", async () => {
    vi.useFakeTimers();
    getGroundednessStatus.mockResolvedValueOnce(status({}));
    const onClose = renderModal();
    await tick(50);
    expect(getGroundednessStatus).toHaveBeenCalledOnce();
    expect(onClose).not.toHaveBeenCalled();

    getGroundednessStatus.mockResolvedValue(status({ state: "on", available: true }));
    // The result reaches the component on a zero-delay timer of its own, a few ms past the poll.
    await tick(SETUP_POLL_MS + 50);

    expect(getGroundednessStatus).toHaveBeenCalledTimes(2);
    expect(onClose).toHaveBeenCalledOnce();
  });
});
