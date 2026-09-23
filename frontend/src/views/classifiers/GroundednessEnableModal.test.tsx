// SPDX-License-Identifier: Apache-2.0
/*
 * GroundednessEnableModal: the mode control picks the prompt and its requirements line, Copy puts the
 * prompt (at the running version's ref) on the clipboard, and the steps follow the status reads from
 * "set up model", through a failed read while Tessary restarts, to the model answering. It then enables
 * the classifier exactly once and says so.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { GroundednessStatus } from "../../api/types";
import { GroundednessEnableModal } from "./GroundednessEnableModal";

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

const STATUS_KEY = ["groundedness-status", "/api/orgs/acme/projects/default", "clf-g"];

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
  return qc;
}

/** The next poll, now rather than in 3.5 s. */
async function poll(qc: QueryClient) {
  await act(async () => {
    await qc.refetchQueries({ queryKey: STATUS_KEY });
  });
}

function stepStates(): (string | null)[] {
  return ["Set up model", "Restart Tessary", "Start scoring"].map((label) =>
    screen.getByText(label).closest("li")!.getAttribute("data-state"),
  );
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
    getGroundednessStatus.mockResolvedValueOnce(status({}));
    const qc = renderModal();

    await screen.findByText("Set up model");
    expect(stepStates()).toEqual(["active", "waiting", "waiting"]);

    // The agent restarts Tessary: nothing answers, which is a step, not an error.
    getGroundednessStatus.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    await poll(qc);
    await waitFor(() => expect(stepStates()).toEqual(["done", "active", "waiting"]));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(/Failed to fetch/)).toBeNull();

    // Back with the model URL set, the model not answering yet: still restarting.
    getGroundednessStatus.mockResolvedValueOnce(status({ configured: true }));
    await poll(qc);
    await waitFor(() => expect(stepStates()).toEqual(["done", "active", "waiting"]));
    expect(setClassifierEnabled).not.toHaveBeenCalled();

    // The model answers: the modal enables the classifier.
    getGroundednessStatus.mockResolvedValue(status({ configured: true, available: true }));
    await poll(qc);

    await screen.findByText("Groundedness enabled");
    expect(stepStates()).toEqual(["done", "done", "done"]);
    screen.getByRole("button", { name: "Done" });

    await poll(qc);
    await poll(qc);
    expect(setClassifierEnabled).toHaveBeenCalledOnce();
    expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", true);
  });
});
