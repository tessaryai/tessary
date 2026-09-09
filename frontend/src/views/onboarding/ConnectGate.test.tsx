// SPDX-License-Identifier: Apache-2.0
/*
 * Pins the "Start with a sample project" loading state (the fix for the multi-second, silent
 * freeze reported against this link — the click POSTs to `/sample-project`, which seeds the demo
 * dataset synchronously on the backend before responding, and the button previously gave no
 * feedback for that whole span). Verifies only the client-visible contract: while the mutation is
 * pending the link is replaced by a spinner + "Setting up…" label, and it reverts if the mutation
 * fails — not the seed itself, which is a backend concern.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AuthProvider } from "../../auth/AuthContext";
import { TenantProvider } from "../../tenant/TenantContext";
import { ToastProvider } from "../../ui/Toast";
import { ConnectGate } from "./ConnectGate";
import { OTLP_PROMPT } from "../components/SourceConnect";
import type { Me, Project } from "../../api/types-auth";

const FAKE_ME: Me = {
  id: "user-fake",
  email: "smoke@example.com",
  orgs: [{ id: "org-fake", slug: "fake-org", name: "Fake Org", role: "owner" }],
  platform_staff: false,
};

const NOT_CONNECTED_STATUS = {
  has_live: false,
  untagged_spans: 0,
  has_tagged_span: false,
  spans_received: 0,
  tagged_spans: 0,
  last_span_at: null,
  service_name: null,
};

// One deferred promise per test, resolved/rejected explicitly so the pending state can be
// observed before it settles — a plain `Promise.resolve` would flip past "pending" before the
// assertion ever runs.
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

let ensureSampleProjectImpl: () => Promise<Project>;
let createApiKeyCalls = 0;

vi.mock("../../api/client", () => ({
  auth: {
    me: () => Promise.resolve(FAKE_ME),
    ensureSampleProject: () => ensureSampleProjectImpl(),
  },
  projectApi: () => ({
    base: "/api/orgs/fake-org/projects/fake-project",
    substrateStatus: () => Promise.resolve(NOT_CONNECTED_STATUS),
    createApiKey: () => {
      createApiKeyCalls += 1;
      return Promise.resolve({ plaintext: "tsy_test_token" });
    },
    createSource: () => Promise.resolve({ id: "source-fake" }),
  }),
  orgApi: () => ({ base: "/api/orgs/fake-org" }),
}));

function renderGate() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={["/orgs/fake-org/projects/fake-project/traces"]}>
            <Routes>
              <Route
                path="/orgs/:orgSlug/projects/:projectSlug/*"
                element={
                  <TenantProvider>
                    <ConnectGate />
                  </TenantProvider>
                }
              />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  createApiKeyCalls = 0;
  vi.restoreAllMocks();
  Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
});

describe("ConnectGate — sample-project link loading state", () => {
  it("swaps the link for a spinner + label while the seed is pending", async () => {
    const { promise } = deferred<Project>();
    ensureSampleProjectImpl = () => promise; // never resolves within this test

    renderGate();

    const link = await screen.findByRole("button", { name: "Start with a sample project" });
    fireEvent.click(link);

    await waitFor(() => {
      expect(screen.queryByText("Setting up your sample project…")).not.toBeNull();
    });
    expect(screen.queryByRole("button", { name: "Start with a sample project" })).toBeNull();
  });

  it("restores the link if the seed request fails", async () => {
    const { promise, reject } = deferred<Project>();
    ensureSampleProjectImpl = () => promise;

    renderGate();

    const link = await screen.findByRole("button", { name: "Start with a sample project" });
    fireEvent.click(link);
    await waitFor(() => {
      expect(screen.queryByText("Setting up your sample project…")).not.toBeNull();
    });

    reject(new Error("boom"));

    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "Start with a sample project" })).not.toBeNull();
    });
  });
});

/*
 * The Bearer Token field mints on the click that takes the value, not on mount — a mounted gate
 * that nobody used must leave no key behind. Once minted it shows an ELIDED token (`tsy_…oken`) so
 * a full bearer secret is not sitting in a screenshot, while the button puts the WHOLE token on the
 * clipboard through the field's explicit `copyValue`.
 */
describe("ConnectGate — copy affordances", () => {
  it("mints nothing until the token control is clicked, then copies the full token", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

    renderGate();

    const mint = await screen.findByRole("button", { name: "Create and copy" });
    expect(createApiKeyCalls).toBe(0);
    expect(screen.queryByText(/^tsy_test/)).toBeNull();

    fireEvent.click(mint);

    await waitFor(() => expect(writeText).toHaveBeenCalledWith("tsy_test_token"));
    expect(createApiKeyCalls).toBe(1);
    expect(writeText).toHaveBeenCalledTimes(1);

    const header = await screen.findByText(/^tsy_test/);
    expect(header.textContent).not.toContain("tsy_test_token"); // elided on screen
  });

  it("copies the connect prompt", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });

    renderGate();

    const copy = await screen.findByRole("button", { name: /Copy prompt/ });
    fireEvent.click(copy);

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(OTLP_PROMPT));
    await waitFor(() => expect(copy.textContent).toContain("Copied"));
  });
});
