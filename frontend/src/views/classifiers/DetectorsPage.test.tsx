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
import { MemoryRouter, useLocation } from "react-router-dom";
import type {
  BehaviorFinding,
  Classifier,
  ClassifierDailyVolume,
  ClassifierEvent,
  ClassifierHealth,
  GroundednessStatus,
} from "../../api/types";
import { ToastProvider } from "../../ui";
import { DetectionRow, DetectorsPage } from "./DetectorsPage";
import { ago } from "./shared";
import { clockTime } from "./groundedness";

const listClassifiers = vi.fn<() => Promise<Classifier[]>>();
const setClassifierEnabled = vi.fn();
const getGroundednessStatus = vi.fn<(id: string) => Promise<GroundednessStatus>>();
const EMPTY_VOLUME: ClassifierDailyVolume = { days: [], trace_totals: [], classifiers: [] };
const getClassifierDailyVolume = vi.fn<() => Promise<ClassifierDailyVolume>>(async () => EMPTY_VOLUME);
const listClassifierHealth = vi.fn<() => Promise<ClassifierHealth[]>>(async () => []);
const listClassifierEvents = vi.fn<(id: string, limit?: number) => Promise<ClassifierEvent[]>>(async () => []);
const listBehaviorFindings = vi.fn();
const analyzeBehaviorFinding = vi.fn();
const resolveBehaviorFinding = vi.fn();

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
        listClassifierEvents,
        listBehaviorFindings,
        analyzeBehaviorFinding,
        resolveBehaviorFinding,
        getClassifierTuning: () => new Promise(() => {}),
        getClassifierDebug: () => new Promise(() => {}),
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
  listClassifierEvents.mockImplementation(async () => []);
  listBehaviorFindings.mockReset();
  analyzeBehaviorFinding.mockReset();
  resolveBehaviorFinding.mockReset();
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

function Search() {
  return <output aria-label="search">{useLocation().search}</output>;
}

function renderPage(route = "/") {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <MemoryRouter initialEntries={[route]}>
      <QueryClientProvider client={qc}>
        <ToastProvider>
          <DetectorsPage />
          <Search />
        </ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return qc;
}

describe("DetectorsPage", () => {
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
  it("keeps the detection count while on in dev, and drops the setting-up flag", async () => {
    // A dev row can carry a caught-up time too, and this browser copied a setup prompt earlier.
    window.localStorage.setItem("tsy-groundedness-setup:acme/default", "2026-09-23T14:00:00Z");
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "on", configured: true, available: true, ever_swept: true, last_caught_up_at: todayAt(14, 3) }),
    );
    renderPage();

    // The flag is cleared once the status reads on, so the row below is drawn from that status.
    await waitFor(() => expect(window.localStorage.getItem("tsy-groundedness-setup:acme/default")).toBeNull());
    const g = await row("Groundedness");
    within(g).getByText("quiet 7d");
    expect(within(g).queryByText(/last run/)).toBeNull();
    expect(within(g).queryByText("Setting up...")).toBeNull();
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

  it("shows the restart notice in the rail while not scoring, and links it at the running version", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({
        state: "not_scoring",
        configured: true,
        ever_swept: true,
        last_scored_at: todayAt(14, 2),
        setup_ref: "v1.3.0",
      }),
    );
    renderPage();

    fireEvent.click(await screen.findByText("Groundedness"));
    fireEvent.click(await screen.findByRole("button", { name: "Restart model" }));

    await screen.findByText("Restart model", { selector: "h2" });
    await screen.findByText(/Restart the Groundedness model on this Mac by following/);
    screen.getByText(
      "https://github.com/tessaryai/tessary/blob/v1.3.0/classifiers/groundedness/setup/groundedness-setup-mac.md#restart",
    );
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
});

describe("DetectorsPage, Groundedness setup", () => {
  it("marks setup under way once the prompt is copied, and clears it when the model answers and it enables", async () => {
    Object.defineProperty(navigator, "clipboard", { value: { writeText: vi.fn(async () => {}) }, configurable: true });
    listClassifiers.mockResolvedValue([groundednessRow()]);
    getGroundednessStatus.mockResolvedValue(status({}));
    setClassifierEnabled.mockResolvedValue(groundednessRow({ enabled: true }));
    const qc = renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Enable Groundedness" }));
    const modal = await screen.findByRole("dialog");
    fireEvent.click(within(modal).getByRole("button", { name: /Copy/ }));

    await waitFor(() => expect(window.localStorage.getItem("tsy-groundedness-setup:acme/default")).not.toBeNull());
    expect(screen.getByText("Setting up...")).toBeTruthy();

    getGroundednessStatus.mockResolvedValue(status({ configured: true, available: true }));
    await qc.invalidateQueries();

    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", true));
    expect(await within(modal).findByText("Groundedness enabled")).toBeTruthy();
    expect(window.localStorage.getItem("tsy-groundedness-setup:acme/default")).toBeNull();
    fireEvent.click(within(modal).getByRole("button", { name: "Done" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("closes the turn-off question without turning it off", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(status({ state: "on", configured: true, available: true, ever_swept: true }));
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Disable Groundedness" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });

  it("closes the restart guide from the rail", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "not_scoring", configured: true, ever_swept: true, last_scored_at: todayAt(14, 2) }),
    );
    renderPage("/?classifier=clf-g");

    fireEvent.click(await screen.findByRole("button", { name: "Restart model" }));
    const modal = (await screen.findByText("Restart model", { selector: "h2" })).closest("dialog")!;
    fireEvent.click(within(modal).getAllByRole("button", { name: "Close" }).at(-1)!);

    await waitFor(() => expect(screen.queryByText("Restart model", { selector: "h2" })).toBeNull());
  });

  it("closes the Frustration enable modal without enabling", async () => {
    listClassifiers.mockResolvedValue([classifier({})]);
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Enable Frustration" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });
});

describe("the detail rail", () => {
  const COST = classifier({
    id: "clf-c",
    classifier_key: "cost_drift",
    name: "Cost drift",
    detector: "cost_drift",
    enabled: true,
  });
  const finding = (id: string, over: Partial<BehaviorFinding> = {}) =>
    ({
      id,
      title: `Cost rose on ${id}`,
      detector: "cost_drift",
      causeKey: `cost_drift:${id}`,
      traceCount: 12,
      triageStatus: "pending",
      triageSummary: null,
      triageVerdict: null,
      humanVerdictAt: null,
      ...over,
    }) as BehaviorFinding;
  const rail = () => screen.getByRole("dialog", { name: "Cost drift detail" });
  const search = () => screen.getByLabelText("search").textContent;

  it("opens on the row pressed and closes back to the catalog", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockResolvedValue({ findings: [] });
    renderPage();

    fireEvent.click(await screen.findByText("Cost drift"));
    expect(search()).toBe("?classifier=clf-c");
    expect(await within(rail()).findByText(/Nothing has drifted/)).toBeTruthy();
    expect(listBehaviorFindings).toHaveBeenCalledWith("cost_drift");

    fireEvent.click(within(rail()).getByRole("button", { name: "Close" }));
    expect(search()).toBe("");
  });

  it("offers triage and the verdicts on an untriaged finding, and only the ruling on a triaged one", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockResolvedValue({
      findings: [
        finding("f-2", {
          triageStatus: "done",
          triageVerdict: "positive",
          triageSummary: "The price change explains it.",
        }),
        finding("f-1"),
        finding("f-3", { triageStatus: "in_flight" }),
      ],
    });
    analyzeBehaviorFinding.mockRejectedValueOnce(new Error("no sandbox")).mockResolvedValue({});
    resolveBehaviorFinding.mockRejectedValueOnce(new Error("locked")).mockResolvedValue({});
    renderPage("/?classifier=clf-c");

    const first = (await within(await screen.findByRole("dialog")).findByText("Cost rose on f-1")).parentElement!;
    const second = within(rail()).getByText("Cost rose on f-2").parentElement!;
    const third = within(rail()).getByText("Cost rose on f-3").parentElement!;
    expect(within(first).getByText("seen 12× · not triaged yet")).toBeTruthy();
    expect(within(second).getByText("seen 12× · triage ruled positive")).toBeTruthy();
    expect((within(second).getByRole("button", { name: "Triaged" }) as HTMLButtonElement).disabled).toBe(true);
    expect(within(second).queryByRole("button", { name: "Absorb as legitimate" })).toBeNull();
    expect(within(first).getByRole("button", { name: "Absorb as legitimate" })).toBeTruthy();
    expect((within(third).getByRole("button", { name: "Triaging…" }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(within(second).getByRole("button", { name: "Full ruling" }));
    expect(within(second).getByText("cost_drift:f-2")).toBeTruthy();
    fireEvent.click(within(second).getByRole("button", { name: "Less" }));
    expect(within(second).queryByText("cost_drift:f-2")).toBeNull();

    fireEvent.click(within(first).getByRole("button", { name: "Run triage" }));
    expect(await within(rail()).findByText("no sandbox")).toBeTruthy();
    fireEvent.click(within(first).getByRole("button", { name: "Run triage" }));
    await waitFor(() => expect(analyzeBehaviorFinding).toHaveBeenLastCalledWith("f-1"));

    const verbs = within(first).getAllByRole("button").filter((b) => b.textContent !== "Run triage");
    fireEvent.click(verbs[0]);
    expect(await within(rail()).findByText("locked")).toBeTruthy();
    fireEvent.click(verbs[1]);
    await waitFor(() => expect(resolveBehaviorFinding).toHaveBeenLastCalledWith("f-1", "not_expected"));
    expect(resolveBehaviorFinding.mock.calls[0]).toEqual(["f-1", "expected"]);
  });

  it("says when the findings could not be read", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockRejectedValue(new Error("findings unavailable"));
    renderPage("/?classifier=clf-c");

    expect(await within(await screen.findByRole("dialog")).findByText("findings unavailable")).toBeTruthy();
  });

  it("summarises each detection's evidence, and opens its trace when it has one", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockResolvedValue({ findings: [] });
    const detection = (id: string, over: Partial<ClassifierEvent>) => event({ id, ...over });
    listClassifierEvents.mockResolvedValue([
      detection("d1", {
        trace_id: "trace-1",
        evidence_json: JSON.stringify({ matched_value: ["a", "b"], pattern: "x".repeat(100), empty: "", none: [], gone: null }),
      }),
      detection("d2", { subject_kind: "session", subject_id: "sess-9", evidence_json: "not json" }),
      detection("d3", { evidence_json: "42", confidence: "low" }),
      detection("d4", { evidence_json: JSON.stringify({ gone: null }) }),
      ...Array.from({ length: 21 }, (_, i) => detection(`e${i}`, {})),
    ]);
    renderPage("/?classifier=clf-c");

    const r = await screen.findByRole("dialog", { name: "Cost drift detail" });
    expect(await within(r).findByText(`matched value a, b · pattern ${"x".repeat(90)}…`)).toBeTruthy();
    expect(within(r).getByText("trace-1").closest("a")!.getAttribute("href")).toBe("/traces/trace-1");
    expect(within(r).getByText("session sess-9").closest("a")).toBeNull();
    expect(within(r).getByText("not json")).toBeTruthy();
    expect(within(r).getByText("42")).toBeTruthy();
    expect(within(r).getByText("low confidence")).toBeTruthy();
    expect(within(r).getByText("25 most recent · select one to open the trace")).toBeTruthy();
    expect(listClassifierEvents).toHaveBeenCalledWith("clf-c", 25);
  });

  it("says a disabled classifier is not sweeping when it has no detections", async () => {
    listClassifiers.mockResolvedValue([classifier({ id: "clf-t", name: "Tool error", detector: "tool_error" })]);
    renderPage("/?classifier=clf-t");

    expect(await screen.findByText(/Disabled, so it isn't sweeping/)).toBeTruthy();
  });

  it("retries a paused classifier from the rail, and says why a retry was refused", async () => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness: "provider_rejected" })]);
    setClassifierEnabled.mockRejectedValueOnce(new Error("key still rejected")).mockResolvedValue({});
    renderPage("/?classifier=clf-1");

    const r = await screen.findByRole("dialog", { name: "Frustration detail" });
    expect(within(r).getByText(/The provider rejected the stored key/)).toBeTruthy();
    fireEvent.click(within(r).getByRole("button", { name: "Retry" }));
    expect(await within(r).findByText("key still rejected")).toBeTruthy();
    fireEvent.click(within(r).getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledTimes(2));
    expect(setClassifierEnabled).toHaveBeenLastCalledWith("clf-1", true);
    await waitFor(() => expect(listClassifiers).toHaveBeenCalledTimes(2));
  });
});
