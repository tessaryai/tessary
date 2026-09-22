// SPDX-License-Identifier: Apache-2.0
/*
 * DetectionRow's stamp: decision 8b adds occurred_at (when the span ran) beside the always-present
 * detected_at (when the sweep checked it), and the row must show the OCCURRED time, falling back to
 * detected_at only when a row predates the migration (occurred_at null).
 *
 * The catalog: switching Frustration on opens its enable modal instead of flipping the switch, since
 * enabling it spends the org's own provider credit, and a paused Frustration names why on its row.
 */
import { beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type { Classifier, ClassifierEvent } from "../../api/types";
import { DetectionRow, DetectorsPage } from "./DetectorsPage";
import { ago } from "./shared";

const listClassifiers = vi.fn<() => Promise<Classifier[]>>();
const setClassifierEnabled = vi.fn();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: {
        base: "/api/orgs/acme/projects/default",
        listClassifiers,
        getClassifierDailyVolume: async () => ({ days: [], trace_totals: [], classifiers: [] }),
        listClassifierHealth: async () => [],
        setClassifierEnabled,
        getModelSettings: () => new Promise(() => {}),
      },
      orgApi: { base: "/api/orgs/acme", listProviderCredentials: () => new Promise(() => {}) },
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
  listClassifiers.mockReset();
  setClassifierEnabled.mockReset();
});

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
    enabled: false,
    mode: "tracking",
    created_at: "2026-09-01T00:00:00Z",
    updated_at: "2026-09-01T00:00:00Z",
    readiness: null,
    ...overrides,
  };
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <MemoryRouter>
      <QueryClientProvider client={qc}>
        <DetectorsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("DetectorsPage", () => {
  it("opens the enable modal for Frustration instead of enabling it", async () => {
    listClassifiers.mockResolvedValue([classifier({})]);
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Enable Frustration" }));

    await screen.findByText("Enable Frustration", { selector: "h2" });
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });

  it("switches any other classifier directly", async () => {
    listClassifiers.mockResolvedValue([
      classifier({ id: "clf-2", classifier_key: "tool_error", name: "Tool error", detector: "tool_error" }),
    ]);
    setClassifierEnabled.mockResolvedValue(classifier({ id: "clf-2", enabled: true }));
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Enable Tool error" }));

    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledWith("clf-2", true));
    expect(screen.queryByText("Enable Frustration", { selector: "h2" })).toBeNull();
  });

  it("names a provider pause on the row and links to Providers", async () => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness: "provider_rejected" })]);
    renderPage();

    const label = await screen.findByText("Provider rejected the key");
    expect(label.closest("a")?.getAttribute("href")).toBe("/orgs/acme/projects/default/settings/providers");
  });

  it("names a missing key on the row", async () => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness: "no_provider" })]);
    renderPage();

    await screen.findByText("No provider key");
  });

  it("renders a refused toggle's error", async () => {
    listClassifiers.mockResolvedValue([
      classifier({ id: "clf-2", classifier_key: "tool_error", name: "Tool error", detector: "tool_error" }),
    ]);
    setClassifierEnabled.mockRejectedValue(new Error("the switch was refused"));
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Enable Tool error" }));

    await screen.findByText(/the switch was refused/);
  });
});

const OLD = new Date(Date.now() - 40 * 24 * 60 * 60 * 1000).toISOString(); // > 30d: an absolute date
const RECENT = new Date().toISOString(); // "just now"

function event(overrides: Partial<ClassifierEvent>): ClassifierEvent {
  return {
    id: "d1",
    classifier_id: "c1",
    classifier_version: 1,
    subject_kind: "span",
    subject_id: "s1",
    trace_id: null,
    project_version_id: null,
    severity: "warn",
    evidence_json: null,
    confidence: "high",
    detected_at: RECENT,
    occurred_at: null,
    ...overrides,
  };
}

describe("DetectionRow", () => {
  it("stamps the row with occurred_at, not detected_at, when both are present", () => {
    render(
      <MemoryRouter>
        <DetectionRow event={event({ occurred_at: OLD, detected_at: RECENT })} />
      </MemoryRouter>,
    );
    expect(screen.queryByText(ago(OLD))).not.toBeNull();
    expect(screen.queryByText("just now")).toBeNull();
  });

  it("falls back to detected_at when occurred_at is null (a row from before migration 0012)", () => {
    render(
      <MemoryRouter>
        <DetectionRow event={event({ occurred_at: null, detected_at: OLD })} />
      </MemoryRouter>,
    );
    expect(screen.queryByText(ago(OLD))).not.toBeNull();
  });
});
