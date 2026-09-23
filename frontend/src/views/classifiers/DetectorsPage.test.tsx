// SPDX-License-Identifier: Apache-2.0
/*
 * DetectionRow's stamp: decision 8b adds occurred_at (when the span ran) beside the always-present
 * detected_at (when the sweep checked it), and the row must show the OCCURRED time, falling back to
 * detected_at only when a row predates the migration (occurred_at null).
 *
 * The catalog: switching Frustration on opens its enable modal instead of flipping the switch, since
 * enabling it spends the org's own provider credit, and a paused Frustration names why on its row.
 *
 * Groundedness: the row says what its model is doing in each state, switching it on opens the setup
 * modal until the model has answered once, and switching it off asks first.
 *
 * Each row's status says whether its sweep is failing, whether it has anything to judge yet, and what
 * it found in 7 days, and never reads "quiet" when the volume is unknown.
 */
import { beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import type {
  Classifier,
  ClassifierDailyVolume,
  ClassifierEvent,
  ClassifierHealth,
  GroundednessStatus,
} from "../../api/types";
import { DetectionRow, DetectorsPage } from "./DetectorsPage";
import { ago } from "./shared";
import { clockTime } from "./groundedness";

const listClassifiers = vi.fn<() => Promise<Classifier[]>>();
const setClassifierEnabled = vi.fn();
const getGroundednessStatus = vi.fn<(id: string) => Promise<GroundednessStatus>>();
const EMPTY_VOLUME: ClassifierDailyVolume = { days: [], trace_totals: [], classifiers: [] };
const getClassifierDailyVolume = vi.fn<() => Promise<ClassifierDailyVolume>>(async () => EMPTY_VOLUME);
const listClassifierHealth = vi.fn<() => Promise<ClassifierHealth[]>>(async () => []);

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
        getClassifierDailyVolume,
        listClassifierHealth,
        setClassifierEnabled,
        getGroundednessStatus,
        listClassifierEvents: async () => [],
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
  getGroundednessStatus.mockReset();
  window.localStorage.clear();
  getClassifierDailyVolume.mockImplementation(async () => EMPTY_VOLUME);
  listClassifierHealth.mockImplementation(async () => []);
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
  return qc;
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

function health(classifierId: string, status: string): ClassifierHealth {
  return {
    classifier_id: classifierId,
    status,
    attempts: status === "failed" ? 5 : 0,
    max_attempts: 5,
    last_error: status === "failed" ? "judge timed out" : null,
    last_swept_at: null,
    next_attempt_at: null,
  };
}

/** The catalog row for the classifier named `name`: its name button and everything beside it. */
async function row(name: string): Promise<HTMLElement> {
  return (await screen.findByText(name)).closest("button")!.parentElement!;
}

describe("DetectorsPage row status", () => {
  const TOOL_ERROR = classifier({ id: "clf-a", classifier_key: "tool_error", name: "Tool error", detector: "tool_error", enabled: true });
  const REFUSAL = classifier({ id: "clf-b", classifier_key: "refusal", name: "Refusal", detector: "refusal", enabled: true });

  // Bug: a detector whose sweep is failing reads as healthy, with its detections and nothing else.
  it("flags a failing sweep on its row, and only on that row", async () => {
    listClassifiers.mockResolvedValue([TOOL_ERROR, REFUSAL]);
    listClassifierHealth.mockResolvedValue([health("clf-a", "failed"), health("clf-b", "pending")]);
    getClassifierDailyVolume.mockResolvedValue({
      ...EMPTY_VOLUME,
      classifiers: [
        { classifier_id: "clf-a", counts: [2, 1] },
        { classifier_id: "clf-b", counts: [0, 1] },
      ],
    });
    renderPage();

    const failing = await row("Tool error");
    await within(failing).findByText("sweep failing");
    within(failing).getByText("3 detections 7d");
    const healthy = await row("Refusal");
    within(healthy).getByText("1 detection 7d");
    expect(within(healthy).queryByText("sweep failing")).toBeNull();
  });

  // Bug: a detector with nothing to judge yet reads "quiet 7d", which says it looked and found nothing.
  it("reads a detector waiting on schemas as waiting, and a silent one as quiet", async () => {
    listClassifiers.mockResolvedValue([{ ...TOOL_ERROR, readiness: "waiting_on_schemas" }, REFUSAL]);
    getClassifierDailyVolume.mockResolvedValue({
      ...EMPTY_VOLUME,
      classifiers: [
        { classifier_id: "clf-a", counts: [0, 0] },
        { classifier_id: "clf-b", counts: [0, 0] },
      ],
    });
    renderPage();

    const quiet = await row("Refusal");
    await within(quiet).findByText("quiet 7d");
    const waiting = await row("Tool error");
    within(waiting).getByText("waiting on schemas");
    expect(within(waiting).queryByText("quiet 7d")).toBeNull();
  });

  // Bug: when the volume read fails, every detector reads "quiet 7d" instead of unknown.
  it("shows a dash, not quiet, when the 7-day volume could not be read", async () => {
    listClassifiers.mockResolvedValue([REFUSAL]);
    getClassifierDailyVolume.mockRejectedValue(new Error("volume unavailable"));
    const qc = renderPage();

    const unknown = await row("Refusal");
    // The dash also shows while the read is in flight, so wait for it to fail first.
    await waitFor(() => expect(qc.isFetching()).toBe(0));
    within(unknown).getByText("–");
    expect(within(unknown).queryByText("quiet 7d")).toBeNull();
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

function groundednessRow(overrides: Partial<Classifier> = {}): Classifier {
  return classifier({
    id: "clf-g",
    classifier_key: "groundedness",
    name: "Groundedness",
    description: "Answers that state things the retrieved documents don't support.",
    detector: "groundedness",
    ...overrides,
  });
}

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
    setup_ref: "main",
    ...overrides,
  };
}

/** Today at 2:02 PM local time, so the row's 12-hour clock reads the same wherever the test runs. */
function todayAt(hour: number, minute: number): string {
  const d = new Date();
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

describe("DetectorsPage, Groundedness", () => {
  it("says a disabled row that was never set up needs setup, and switching it on opens the setup modal", async () => {
    listClassifiers.mockResolvedValue([groundednessRow()]);
    getGroundednessStatus.mockResolvedValue(status({}));
    renderPage();

    await screen.findByText("needs setup");
    fireEvent.click(screen.getByRole("switch", { name: "Enable Groundedness" }));

    await screen.findByText("Enable Groundedness", { selector: "h2" });
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });

  it("says setting up once a setup prompt was copied in this browser", async () => {
    window.localStorage.setItem("tsy-groundedness-setup:acme/default", "2026-09-23T14:00:00Z");
    listClassifiers.mockResolvedValue([groundednessRow()]);
    getGroundednessStatus.mockResolvedValue(status({}));
    renderPage();

    await screen.findByText("Setting up...");
  });

  it("keeps the detection count while on in dev", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(status({ state: "on", configured: true, available: true, ever_swept: true }));
    renderPage();

    await screen.findByText("quiet 7d");
  });

  it("names the last run while on in production", async () => {
    const caughtUp = todayAt(14, 3);
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "on", mode: "production", configured: true, ever_swept: true, last_caught_up_at: caughtUp }),
    );
    renderPage();

    await screen.findByText(`last run ${clockTime(caughtUp)}`);
    expect(clockTime(caughtUp)).toBe("2:03 PM");
  });

  it("says since when nothing was scored while the model is down", async () => {
    const scored = todayAt(14, 2);
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "not_scoring", configured: true, ever_swept: true, last_scored_at: scored }),
    );
    renderPage();

    await screen.findByText("No scores since 2:02 PM");
  });

  it("shows the restart notice in the rail while not scoring", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "not_scoring", configured: true, ever_swept: true, last_scored_at: todayAt(14, 2) }),
    );
    renderPage();

    fireEvent.click(await screen.findByText("Groundedness"));
    fireEvent.click(await screen.findByRole("button", { name: "Restart model" }));

    await screen.findByText("Restart model", { selector: "h2" });
    await screen.findByText(/Restart the Groundedness model on this Mac by following/);
  });

  it("says off for a disabled row that was set up, and switching it on enables it directly", async () => {
    listClassifiers.mockResolvedValue([groundednessRow()]);
    getGroundednessStatus.mockResolvedValue(status({ configured: true, available: true, ever_swept: true }));
    setClassifierEnabled.mockResolvedValue(groundednessRow({ enabled: true }));
    renderPage();

    await screen.findByText("off");
    fireEvent.click(screen.getByRole("switch", { name: "Enable Groundedness" }));

    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", true));
    expect(screen.queryByText("Enable Groundedness", { selector: "h2" })).toBeNull();
  });

  it("asks before switching it off", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(status({ state: "on", configured: true, available: true, ever_swept: true }));
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Disable Groundedness" }));

    await screen.findByText("Turn off Groundedness?", { selector: "h2" });
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });
});
