// SPDX-License-Identifier: Apache-2.0
/*
 * GroundednessEnableModal: the mode control picks the prompt and its requirements line, Copy puts the
 * prompt (at the running version's ref) on the clipboard, and the steps follow the status reads from
 * "set up model", through a failed read while Tessary restarts, to the model answering. It then enables
 * the classifier exactly once and says so.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError, type GroundednessStatus } from "../../api/types";
import { GroundednessEnableModal, SETUP_POLL_MS } from "./GroundednessEnableModal";

const getGroundednessStatus = vi.fn<(id: string) => Promise<GroundednessStatus>>();
const setClassifierEnabled = vi.fn(async () => ({}));

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", getGroundednessStatus, setClassifierEnabled },
    }),
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
  setClassifierEnabled.mockClear();
});

function status(overrides: Partial<GroundednessStatus>): GroundednessStatus {
  return {
    state: "off",
    mode: "dev",
    configured: false,
    available: false,
    reason: "no encoder URL configured",
    checked_at: null,
    ever_swept: false,
    last_scored_at: null,
    last_caught_up_at: null,
    setup_ref: "v1.3.0",
    ...overrides,
  };
}

function renderModal(onPromptCopied = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <GroundednessEnableModal
        classifierId="clf-g"
        onClose={() => {}}
        onEnabled={() => {}}
        onPromptCopied={onPromptCopied}
      />
    </QueryClientProvider>,
  );
}

/** Moves the fake clock `ms` on and lets the reads and renders it starts settle. */
async function tick(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

/**
 * The modal's next status poll, on its own interval, then a few ms more: the query's result reaches the
 * component on a zero-delay timer of its own, set after the read settles.
 */
async function poll() {
  await tick(SETUP_POLL_MS);
  await tick(50);
}

/** The step in progress, or null once every step is done. */
function activeStep(): string | null {
  const steps = within(screen.getByRole("list", { name: "Setup steps" }));
  expect(steps.getAllByRole("listitem").map((li) => li.textContent)).toEqual([
    "Set up model",
    "Restart Tessary",
    "Start scoring",
  ]);
  return steps.queryByRole("listitem", { current: "step" })?.textContent ?? null;
}

describe("GroundednessEnableModal", () => {
  it("swaps the prompt and the requirements line with where the model runs", async () => {
    getGroundednessStatus.mockResolvedValue(status({}));
    renderModal();

    await screen.findByText(/Set up the Groundedness classifier on this Mac by following/);
    screen.getByText("Requires Apple silicon, 16 GB of memory, and 6 GB of free disk.");

    fireEvent.click(screen.getByRole("button", { name: "AWS (production)" }));

    await screen.findByText(/Deploy the Groundedness classifier's model on AWS by following/);
    screen.getByText("Requires a signed-in AWS CLI and Tessary running on AWS, in the same region.");
    expect(screen.queryByText(/on this Mac by following/)).toBeNull();
  });

  it("starts on the backend's mode", async () => {
    getGroundednessStatus.mockResolvedValue(status({ mode: "production" }));
    renderModal();

    await screen.findByText(/on AWS by following/);
  });

  it("copies the prompt, linked at the running version's tag", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    const onPromptCopied = vi.fn();
    getGroundednessStatus.mockResolvedValue(status({}));
    renderModal(onPromptCopied);

    fireEvent.click(await screen.findByRole("button", { name: "Copy prompt" }));

    await waitFor(() => expect(onPromptCopied).toHaveBeenCalledOnce());
    expect(writeText).toHaveBeenCalledWith(
      "Set up the Groundedness classifier on this Mac by following " +
        "https://github.com/tessaryai/tessary/blob/v1.3.0/classifiers/groundedness/setup/groundedness-setup-mac.md",
    );
  });

  it("moves through the steps as the setup runs, then enables once and says so", async () => {
    vi.useFakeTimers();
    getGroundednessStatus.mockResolvedValueOnce(status({}));
    renderModal();

    await tick(50);
    expect(activeStep()).toBe("Set up model");

    // The agent restarts Tessary: nothing answers, which is a step, not an error.
    getGroundednessStatus.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await poll();
    expect(getGroundednessStatus).toHaveBeenCalledTimes(2);
    expect(activeStep()).toBe("Restart Tessary");
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(/Failed to fetch/)).toBeNull();

    // Back with the model URL set, the model not answering yet: still restarting.
    getGroundednessStatus.mockResolvedValueOnce(status({ configured: true }));
    await poll();
    expect(activeStep()).toBe("Restart Tessary");
    expect(setClassifierEnabled).not.toHaveBeenCalled();

    // The model answers: the modal enables the classifier.
    getGroundednessStatus.mockResolvedValue(status({ configured: true, available: true }));
    await poll();

    expect(screen.getByText("Groundedness enabled")).toBeTruthy();
    expect(activeStep()).toBeNull();
    screen.getByRole("button", { name: "Done" });

    await poll();
    await poll();
    expect(setClassifierEnabled).toHaveBeenCalledOnce();
    expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", true);
  });

  it("moves to the restart step when the model URL turns up set, with no failed read between", async () => {
    vi.useFakeTimers();
    getGroundednessStatus.mockResolvedValueOnce(status({}));
    renderModal();
    await tick(50);
    expect(activeStep()).toBe("Set up model");

    getGroundednessStatus.mockResolvedValue(status({ configured: true }));
    await poll();

    expect(activeStep()).toBe("Restart Tessary");
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });

  it("shows a refused read as an error, not as the restart", async () => {
    vi.useFakeTimers();
    getGroundednessStatus.mockResolvedValueOnce(status({}));
    renderModal();
    await tick(50);

    getGroundednessStatus.mockRejectedValue(
      new ApiError(403, { code: "auth.forbidden", message: "only an owner can read this" }),
    );
    await poll();

    expect(screen.getByRole("alert").textContent).toBe("auth.forbidden: only an owner can read this");
    expect(activeStep()).toBe("Set up model");
  });
});
