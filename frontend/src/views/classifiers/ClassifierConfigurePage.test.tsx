// SPDX-License-Identifier: Apache-2.0
/*
 * One classifier's configure page: everything the catalog row and its detail rail did, on a page of its own.
 *
 * DetectionRow's stamp: decision 8b adds occurred_at (when the span ran) beside the always-present detected_at
 * (when the sweep checked it), and the row must show the OCCURRED time, falling back to detected_at only when a row
 * predates the migration (occurred_at null).
 *
 * Switching Frustration on opens its enable modal instead of flipping the switch, since enabling it spends the
 * org's own provider credit, and a paused classifier names why. Groundedness says what its model is doing in each
 * state, switching it on opens the setup modal until the model has answered once, and switching it off asks first.
 *
 * The status beside the switch says whether the sweep is failing, whether the classifier has anything to judge yet,
 * and what it found in 7 days, and never reads "quiet" when the volume is unknown.
 */
import { describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach } from "vitest";
import type {
  BehaviorFinding,
  Classifier,
  ClassifierDailyVolume,
  ClassifierEvent,
  ClassifierHealth,
  GroundednessStatus,
} from "../../api/types";
import { currentLocation, renderRoute } from "../../test/render";
import { chartReadsStale, seedChartReads } from "../../test/chartReads";
import { ClassifierConfigurePage, DetectionRow } from "./ClassifierConfigurePage";
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
const getClassifierTuning = vi.fn(() => new Promise(() => {}));
const listClassifierCallSites = vi.fn(() => new Promise(() => {}));

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
        getClassifierTuning,
        listClassifierCallSites,
        getClassifierDebug: () => new Promise(() => {}),
        getModelSettings: () => new Promise(() => {}),
      },
      orgApi: { base: "/api/orgs/acme", listProviderCredentials: () => new Promise(() => {}) },
    }),
  };
});

afterEach(() => {
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
  getClassifierTuning.mockClear();
  listClassifierCallSites.mockClear();
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
    call_site_ids: null,
    ...overrides,
  };
}

const renderPage = (id = "clf-1") =>
  renderRoute(<ClassifierConfigurePage />, {
    route: `/orgs/acme/projects/default/classifiers/${id}`,
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "classifiers/:classifierId",
  }).queryClient;
const section = (title: string) => screen.getByRole("heading", { level: 2, name: title }).closest("section")!;
const maybeSection = (title: string) => screen.queryByRole("heading", { level: 2, name: title });

describe("the configure page", () => {
  // Bug: a page that names another classifier, or a breadcrumb that leads anywhere but back to Classifiers.
  it("names the classifier with its description, under a breadcrumb back to Classifiers", async () => {
    listClassifiers.mockResolvedValue([
      classifier({ id: "clf-0", name: "Tool errors", detector: "tool_error" }),
      classifier({ description: "Conversations where the user grew frustrated with the agent." }),
    ]);
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "Frustration" })).toBeTruthy();
    expect(screen.getByText("Configure classifier")).toBeTruthy();
    expect(screen.getByText("Conversations where the user grew frustrated with the agent.")).toBeTruthy();
    const crumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    fireEvent.click(within(crumbs).getByRole("link", { name: "Classifiers" }));
    expect(currentLocation()).toBe("/orgs/acme/projects/default/classifiers");
  });

  // Bug: an old bookmark or a deleted classifier's id renders a blank page with no way back.
  it("says when no classifier has that id, and links back to Classifiers", async () => {
    listClassifiers.mockResolvedValue([classifier({})]);
    renderPage("clf-gone");

    expect(await screen.findByText("No classifier with this id in this project.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Back to Classifiers" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/classifiers",
    );
  });

  it("says why the classifiers could not be read", async () => {
    listClassifiers.mockRejectedValue(new Error("classifiers unavailable"));
    renderPage();

    expect(await screen.findByText("classifiers unavailable")).toBeTruthy();
    expect(screen.queryByText("No classifier with this id in this project.")).toBeNull();
  });

  // Bug: Tool errors offered a call-site list the server refuses (it buckets by tool), or a classifier other than
  // the two drift ones given the drift form.
  it("shows the call-site form and the tuning form only where they apply", async () => {
    listClassifiers.mockResolvedValue([classifier({ id: "clf-t", name: "Tool errors", detector: "tool_error" })]);
    renderPage("clf-t");
    await screen.findByRole("heading", { level: 1, name: "Tool errors" });
    await within(section("Status")).findByText("Every tool");
    expect(maybeSection("Call sites")).toBeNull();
    expect(maybeSection("Tuning")).toBeNull();
    expect(listClassifierCallSites).not.toHaveBeenCalled();
  });

  // Bug: Frustration given a picker of its own (its list is gone; it shares call_site_ids), or no word on what
  // limiting it is for.
  it("gives Frustration the shared call-site list, with a tip on which call sites to keep", async () => {
    listClassifiers.mockResolvedValue([classifier({})]);
    renderPage();

    const callSites = await waitFor(() => section("Call sites"));
    expect(within(callSites).getByText(/limit it to the call sites that reply to the user/)).toBeTruthy();
    expect(listClassifierCallSites).toHaveBeenCalled();
    expect(maybeSection("Tuning")).toBeNull();
  });

  // Bug: Status says nothing about where Frustration runs, as if it still kept a list of its own.
  it("says where Frustration runs in Status, like every other classifier", async () => {
    listClassifiers.mockResolvedValue([classifier({})]);
    renderPage();
    expect(await within(await waitFor(() => section("Status"))).findByText("Every call site")).toBeTruthy();
  });

  it("counts Frustration's call sites in Status when it is limited", async () => {
    listClassifiers.mockResolvedValue([classifier({ call_site_ids: ["a", "b"] })]);
    renderPage();
    expect(await within(await waitFor(() => section("Status"))).findByText("2 call sites")).toBeTruthy();
  });

  it("gives a drift classifier the call-site list and the tuning form", async () => {
    listClassifiers.mockResolvedValue([
      classifier({ id: "clf-c", name: "Cost drift", detector: "cost_drift", call_site_ids: ["a", "b"] }),
    ]);
    listBehaviorFindings.mockResolvedValue({ findings: [] });
    renderPage("clf-c");

    await screen.findByRole("heading", { level: 2, name: "Tuning" });
    expect(getClassifierTuning).toHaveBeenCalledWith("clf-c");
    expect(listClassifierCallSites).toHaveBeenCalled();
    expect(within(section("Status")).getByText("2 call sites")).toBeTruthy();
  });

  // Bug: one explanation for every classifier, telling a secret-leak reader about a baseline it never learns.
  it.each([
    ["frustration", /learns the usual flagged rate for each call site/],
    ["tool_error", /learns the usual failure rate for each tool/],
    ["duration_drift", /learns how .* usually spread/],
    ["secret_leak", /enough detections land within one window/],
    ["regex", /enough detections land within one window/],
  ])("explains how %s opens a finding", async (detector, words) => {
    listClassifiers.mockResolvedValue([classifier({ detector, name: "Watch" })]);
    listBehaviorFindings.mockResolvedValue({ findings: [] });
    renderPage();

    expect(await within(await waitFor(() => section("How a finding opens"))).findByText(words)).toBeTruthy();
  });

  it("names a provider pause beside the switch and links to Providers", async () => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness: "provider_rejected" })]);
    renderPage();

    const label = await screen.findByText("Provider rejected the key");
    expect(label.closest("a")?.getAttribute("href")).toBe("/orgs/acme/projects/default/settings/providers");
  });

  it("names a missing key", async () => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness: "no_provider" })]);
    renderPage();

    await screen.findByText("No provider key");
  });

  it.each([
    ["no_credit", "No credit left", /used all of its credit/],
    ["platform_unavailable", "Provider unavailable", /Nothing needs to change on your side/],
  ])("names a %s pause and explains it in Status", async (readiness, label, explained) => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness })]);
    renderPage();

    await screen.findByText(label);
    within(section("Status")).getByText(explained);
  });

  it("renders a refused toggle's error", async () => {
    listClassifiers.mockResolvedValue([classifier({ classifier_key: "tool_error", name: "Tool error", detector: "tool_error" })]);
    setClassifierEnabled.mockRejectedValue(new Error("the switch was refused"));
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Enable Tool error" }));

    await screen.findByText(/the switch was refused/);
  });

  it("turns a classifier off with the switch", async () => {
    listClassifiers.mockResolvedValue([classifier({ name: "Tool error", detector: "tool_error", enabled: true })]);
    setClassifierEnabled.mockResolvedValue({});
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Disable Tool error" }));

    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledWith("clf-1", false));
    await waitFor(() => expect(listClassifiers).toHaveBeenCalledTimes(2));
  });

  // Bug: back on Classifiers within the staleTime, the Configure menu still says "On" and the card still draws.
  it("marks the Classifiers charts and menu stale when the switch flips", async () => {
    listClassifiers.mockResolvedValue([classifier({ name: "Tool error", detector: "tool_error", enabled: true })]);
    setClassifierEnabled.mockResolvedValue({});
    const qc = renderPage();
    seedChartReads(qc);

    fireEvent.click(await screen.findByRole("switch", { name: "Disable Tool error" }));

    await waitFor(() => expect(chartReadsStale(qc)).toEqual([true, true]));
  });

  it("carries the mode and the call sites in Status", async () => {
    listClassifiers.mockResolvedValue([
      classifier({ name: "Secret leak", detector: "secret_leak", mode: "blocking", call_site_ids: ["a"] }),
    ]);
    renderPage();

    const status = await waitFor(() => section("Status"));
    expect(within(status).getByText("blocking")).toBeTruthy();
    expect(within(status).getByText("1 call site")).toBeTruthy();
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

describe("the status beside the switch", () => {
  const TOOL_ERROR = classifier({ id: "clf-a", classifier_key: "tool_error", name: "Tool error", detector: "tool_error", enabled: true });

  // Bug: a classifier whose sweep is failing reads as healthy, with its detections and nothing else.
  it("flags a failing sweep, and explains it in Status", async () => {
    listClassifiers.mockResolvedValue([TOOL_ERROR]);
    listClassifierHealth.mockResolvedValue([health("clf-a", "failed"), health("clf-b", "pending")]);
    getClassifierDailyVolume.mockResolvedValue({
      ...EMPTY_VOLUME,
      classifiers: [
        { classifier_id: "clf-a", counts: [2, 1] },
        { classifier_id: "clf-b", counts: [0, 1] },
      ],
    });
    renderPage("clf-a");

    await screen.findByText("sweep failing");
    screen.getByText("3 detections 7d");
    within(section("Status")).getByText("Sweep failing: judge timed out");
  });

  it("does not flag a healthy sweep, and counts one detection in the singular", async () => {
    listClassifiers.mockResolvedValue([TOOL_ERROR]);
    listClassifierHealth.mockResolvedValue([health("clf-a", "pending")]);
    getClassifierDailyVolume.mockResolvedValue({ ...EMPTY_VOLUME, classifiers: [{ classifier_id: "clf-a", counts: [0, 1] }] });
    renderPage("clf-a");

    await screen.findByText("1 detection 7d");
    expect(screen.queryByText("sweep failing")).toBeNull();
  });

  // Bug: a classifier with nothing to judge yet reads "quiet 7d", which says it looked and found nothing.
  it("reads a classifier waiting on schemas as waiting, and a silent one as quiet", async () => {
    listClassifiers.mockResolvedValue([{ ...TOOL_ERROR, readiness: "waiting_on_schemas" }]);
    getClassifierDailyVolume.mockResolvedValue({ ...EMPTY_VOLUME, classifiers: [{ classifier_id: "clf-a", counts: [0, 0] }] });
    renderPage("clf-a");

    await screen.findByText("waiting on schemas");
    expect(screen.queryByText("quiet 7d")).toBeNull();
    within(section("Status")).getByText(/No call site declares an output schema yet/);
  });

  it("reads a silent classifier as quiet", async () => {
    listClassifiers.mockResolvedValue([TOOL_ERROR]);
    getClassifierDailyVolume.mockResolvedValue({ ...EMPTY_VOLUME, classifiers: [{ classifier_id: "clf-a", counts: [0, 0] }] });
    renderPage("clf-a");

    await screen.findByText("quiet 7d");
  });

  // Bug: when the volume read fails, the classifier reads "quiet 7d" instead of unknown.
  it("shows a dash, not quiet, when the 7-day volume could not be read", async () => {
    listClassifiers.mockResolvedValue([TOOL_ERROR]);
    getClassifierDailyVolume.mockRejectedValue(new Error("volume unavailable"));
    const qc = renderPage("clf-a");

    await screen.findByRole("heading", { level: 1, name: "Tool error" });
    // The dash also shows while the read is in flight, so wait for it to fail first.
    await waitFor(() => expect(qc.isFetching()).toBe(0));
    screen.getByText("–");
    expect(screen.queryByText("quiet 7d")).toBeNull();
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
    session_id: null,
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
    renderRoute(<DetectionRow event={event({ occurred_at: OLD, detected_at: RECENT })} />);
    expect(screen.queryByText(ago(OLD))).not.toBeNull();
    expect(screen.queryByText("just now")).toBeNull();
  });

  it("falls back to detected_at when occurred_at is null (a row from before migration 0012)", () => {
    renderRoute(<DetectionRow event={event({ occurred_at: null, detected_at: OLD })} />);
    expect(screen.queryByText(ago(OLD))).not.toBeNull();
  });

  it("opens the session on the flagged span, not the trace, when the detection belongs to a session", () => {
    renderRoute(
      <DetectionRow event={event({ trace_id: "t1", session_id: "sess-1", subject_kind: "span", subject_id: "sp1" })} />,
      { route: "/classifiers", path: "classifiers" },
    );
    fireEvent.click(screen.getByRole("link"));
    expect(currentLocation()).toBe("/sessions/sess-1?span=sp1");
  });

  it("opens the trace when the detection has no session (anonymous traffic)", () => {
    renderRoute(
      <DetectionRow event={event({ trace_id: "t1", session_id: null, subject_kind: "span", subject_id: "sp1" })} />,
      { route: "/classifiers", path: "classifiers" },
    );
    fireEvent.click(screen.getByRole("link"));
    expect(currentLocation()).toBe("/traces/t1");
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

/** Today at the given local time, so the 12-hour clock reads the same wherever the test runs. */
function todayAt(hour: number, minute: number): string {
  const d = new Date();
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

describe("Groundedness", () => {
  it("keeps the detection count while on in dev, and drops the setting-up flag", async () => {
    // A dev row can carry a caught-up time too, and this browser copied a setup prompt earlier.
    window.localStorage.setItem("tsy-groundedness-setup:acme/default", "2026-09-23T14:00:00Z");
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "on", configured: true, available: true, ever_swept: true, last_caught_up_at: todayAt(14, 3) }),
    );
    renderPage("clf-g");

    // The flag is cleared once the status reads on, so the status below is drawn from that status.
    await waitFor(() => expect(window.localStorage.getItem("tsy-groundedness-setup:acme/default")).toBeNull());
    await screen.findByText("quiet 7d");
    expect(screen.queryByText(/last run/)).toBeNull();
    expect(screen.queryByText("Setting up...")).toBeNull();
  });

  it("names the last run while on in production, and the model's facts in Status", async () => {
    const caughtUp = todayAt(14, 3);
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "on", mode: "production", configured: true, ever_swept: true, last_caught_up_at: caughtUp }),
    );
    renderPage("clf-g");

    await screen.findByText(`last run ${clockTime(caughtUp)}`);
    expect(clockTime(caughtUp)).toBe("2:03 PM");
    expect(within(section("Status")).getByText("Hourly")).toBeTruthy();
    expect(within(section("Status")).getByText("groundedness-classifier-v1")).toBeTruthy();
  });

  it("shows the restart notice while not scoring, and links it at the running version", async () => {
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
    renderPage("clf-g");

    fireEvent.click(await screen.findByRole("button", { name: "Restart model" }));

    await screen.findByText("Restart model", { selector: "h2" });
    await screen.findByText(/Restart the Groundedness model on this Mac by following/);
    screen.getByText(
      "https://github.com/tessaryai/tessary/blob/v1.3.0/classifiers/groundedness/setup/groundedness-setup-mac.md#restart",
    );
  });

  it("says off for a disabled classifier that was set up, and switching it on enables it directly", async () => {
    listClassifiers.mockResolvedValue([groundednessRow()]);
    getGroundednessStatus.mockResolvedValue(status({ configured: true, available: true, ever_swept: true }));
    setClassifierEnabled.mockResolvedValue(groundednessRow({ enabled: true }));
    renderPage("clf-g");

    await screen.findByText("off");
    fireEvent.click(screen.getByRole("switch", { name: "Enable Groundedness" }));

    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", true));
    expect(screen.queryByText("Enable Groundedness", { selector: "h2" })).toBeNull();
  });

  it("marks setup under way once the prompt is copied, and clears it when the model answers and it enables", async () => {
    Object.defineProperty(navigator, "clipboard", { value: { writeText: vi.fn(async () => {}) }, configurable: true });
    listClassifiers.mockResolvedValue([groundednessRow()]);
    getGroundednessStatus.mockResolvedValue(status({}));
    setClassifierEnabled.mockResolvedValue(groundednessRow({ enabled: true }));
    const qc = renderPage("clf-g");

    await screen.findByText("needs setup");
    fireEvent.click(screen.getByRole("switch", { name: "Enable Groundedness" }));
    const modal = await screen.findByRole("dialog");
    fireEvent.click(within(modal).getByRole("button", { name: /Copy/ }));

    await waitFor(() => expect(window.localStorage.getItem("tsy-groundedness-setup:acme/default")).not.toBeNull());
    expect(screen.getByText("Setting up...")).toBeTruthy();

    getGroundednessStatus.mockResolvedValue(status({ configured: true, available: true }));
    // Only the status: the call-site list on this page never answers here, and awaiting every query would hang.
    await qc.invalidateQueries({ queryKey: ["groundedness-status"] });

    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", true));
    expect(await within(modal).findByText("Groundedness enabled")).toBeTruthy();
    expect(window.localStorage.getItem("tsy-groundedness-setup:acme/default")).toBeNull();
    fireEvent.click(within(modal).getByRole("button", { name: "Done" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("closes the turn-off question without turning it off", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(status({ state: "on", configured: true, available: true, ever_swept: true }));
    renderPage("clf-g");

    fireEvent.click(await screen.findByRole("switch", { name: "Disable Groundedness" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });

  // Bug: Groundedness turned off here still draws its card on Classifiers, with no "off" chip, until the staleTime ends.
  it("marks the Classifiers charts and menu stale when it is turned off", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(status({ state: "on", configured: true, available: true, ever_swept: true }));
    setClassifierEnabled.mockResolvedValue({});
    const qc = renderPage("clf-g");
    seedChartReads(qc);

    fireEvent.click(await screen.findByRole("switch", { name: "Disable Groundedness" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Turn off" }));

    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledWith("clf-g", false));
    await waitFor(() => expect(chartReadsStale(qc)).toEqual([true, true]));
  });

  it("closes the restart guide", async () => {
    listClassifiers.mockResolvedValue([groundednessRow({ enabled: true })]);
    getGroundednessStatus.mockResolvedValue(
      status({ state: "not_scoring", configured: true, ever_swept: true, last_scored_at: todayAt(14, 2) }),
    );
    renderPage("clf-g");

    fireEvent.click(await screen.findByRole("button", { name: "Restart model" }));
    const modal = (await screen.findByText("Restart model", { selector: "h2" })).closest("dialog")!;
    fireEvent.click(within(modal).getAllByRole("button", { name: "Close" }).at(-1)!);

    await waitFor(() => expect(screen.queryByText("Restart model", { selector: "h2" })).toBeNull());
  });

  // Bug: the status read fires for every classifier and 422s, since only Groundedness has one.
  it("reads the model's status only for Groundedness", async () => {
    listClassifiers.mockResolvedValue([classifier({ name: "Tool error", detector: "tool_error" })]);
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "Tool error" });
    expect(getGroundednessStatus).not.toHaveBeenCalled();
  });
});

describe("Frustration", () => {
  it("closes the enable modal without enabling", async () => {
    listClassifiers.mockResolvedValue([classifier({})]);
    renderPage();

    fireEvent.click(await screen.findByRole("switch", { name: "Enable Frustration" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(setClassifierEnabled).not.toHaveBeenCalled();
  });
});

describe("a drift classifier's findings, detections and reset", () => {
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

  it("lists only this classifier's findings, and says when it has none", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockResolvedValue({ findings: [] });
    renderPage("clf-c");

    expect(await within(await waitFor(() => section("Findings"))).findByText(/Nothing has drifted/)).toBeTruthy();
    expect(listBehaviorFindings).toHaveBeenCalledWith("cost_drift");
  });

  // Bug: a classifier that opens no drift findings given a Findings section that is empty forever.
  it("has no Findings section for a classifier that is not a drift one", async () => {
    listClassifiers.mockResolvedValue([classifier({ name: "Secret leak", detector: "secret_leak" })]);
    renderPage();

    await screen.findByRole("heading", { level: 2, name: "Recent detections" });
    expect(maybeSection("Findings")).toBeNull();
    expect(listBehaviorFindings).not.toHaveBeenCalled();
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
    renderPage("clf-c");

    const first = (await screen.findByText("Cost rose on f-1")).parentElement!;
    const findings = section("Findings");
    const second = within(findings).getByText("Cost rose on f-2").parentElement!;
    const third = within(findings).getByText("Cost rose on f-3").parentElement!;
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
    expect(await within(findings).findByText("no sandbox")).toBeTruthy();
    fireEvent.click(within(first).getByRole("button", { name: "Run triage" }));
    await waitFor(() => expect(analyzeBehaviorFinding).toHaveBeenLastCalledWith("f-1"));

    const verbs = within(first).getAllByRole("button").filter((b) => b.textContent !== "Run triage");
    fireEvent.click(verbs[0]);
    expect(await within(findings).findByText("locked")).toBeTruthy();
    fireEvent.click(verbs[1]);
    await waitFor(() => expect(resolveBehaviorFinding).toHaveBeenLastCalledWith("f-1", "not_expected"));
    expect(resolveBehaviorFinding.mock.calls[0]).toEqual(["f-1", "expected"]);
  });

  // Bug: Triage, reached from the breadcrumb within the staleTime, still lists a finding just ruled or triaged here.
  it("marks Triage's findings stale when a finding is triaged or ruled here", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockResolvedValue({ findings: [finding("f-1")] });
    analyzeBehaviorFinding.mockResolvedValue({});
    resolveBehaviorFinding.mockResolvedValue({});
    const qc = renderPage("clf-c");
    const triageKey = ["behavior-findings", "/api/orgs/acme/projects/default", "all"];
    const triageStale = () => qc.getQueryState(triageKey)?.isInvalidated ?? false;
    const row = (await screen.findByText("Cost rose on f-1")).parentElement!;

    qc.setQueryData(triageKey, { findings: [] });
    fireEvent.click(within(row).getByRole("button", { name: "Run triage" }));
    await waitFor(() => expect(triageStale()).toBe(true));

    const verbs = within(row).getAllByRole("button").filter((b) => b.textContent !== "Run triage");
    for (const verb of verbs) {
      qc.setQueryData(triageKey, { findings: [] });
      fireEvent.click(verb);
      await waitFor(() => expect(triageStale()).toBe(true));
    }
    expect(resolveBehaviorFinding.mock.calls).toEqual([
      ["f-1", "expected"],
      ["f-1", "not_expected"],
    ]);
  });

  it("says when the findings could not be read", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockRejectedValue(new Error("findings unavailable"));
    renderPage("clf-c");

    expect(await screen.findByText("findings unavailable")).toBeTruthy();
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
    renderPage("clf-c");

    const r = await waitFor(() => section("Recent detections"));
    expect(await within(r).findByText(`matched value a, b · pattern ${"x".repeat(90)}…`)).toBeTruthy();
    expect(within(r).getByText("trace-1").closest("a")!.getAttribute("href")).toBe(
      "/orgs/acme/projects/default/traces/trace-1",
    );
    expect(within(r).getByText("session sess-9").closest("a")).toBeNull();
    expect(within(r).getByText("not json")).toBeTruthy();
    expect(within(r).getByText("42")).toBeTruthy();
    expect(within(r).getByText("low confidence")).toBeTruthy();
    expect(within(r).getByText("25 most recent · select one to open its session")).toBeTruthy();
    expect(listClassifierEvents).toHaveBeenCalledWith("clf-c", 25);
  });

  it("says a disabled classifier is not sweeping when it has no detections", async () => {
    listClassifiers.mockResolvedValue([classifier({ id: "clf-t", name: "Tool error", detector: "tool_error" })]);
    renderPage("clf-t");

    expect(await screen.findByText(/Disabled, so it isn't sweeping/)).toBeTruthy();
  });

  it("retries a paused classifier from Status, and says why a retry was refused", async () => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness: "provider_rejected" })]);
    setClassifierEnabled.mockRejectedValueOnce(new Error("key still rejected")).mockResolvedValue({});
    renderPage();

    const s = await waitFor(() => section("Status"));
    expect(within(s).getByText(/The provider rejected the stored key/)).toBeTruthy();
    fireEvent.click(within(s).getByRole("button", { name: "Retry" }));
    expect(await within(s).findByText("key still rejected")).toBeTruthy();
    fireEvent.click(within(s).getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(setClassifierEnabled).toHaveBeenCalledTimes(2));
    expect(setClassifierEnabled).toHaveBeenLastCalledWith("clf-1", true);
    await waitFor(() => expect(listClassifiers).toHaveBeenCalledTimes(2));
  });

  // Bug: a paused classifier retried here still reads "Waiting" in the Configure menu on Classifiers.
  it("marks the Classifiers charts and menu stale when a retry lands", async () => {
    listClassifiers.mockResolvedValue([classifier({ enabled: true, readiness: "provider_rejected" })]);
    setClassifierEnabled.mockResolvedValue({});
    const qc = renderPage();
    seedChartReads(qc);

    fireEvent.click(within(await waitFor(() => section("Status"))).getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(chartReadsStale(qc)).toEqual([true, true]));
  });

  it("opens the reset question from Reset", async () => {
    listClassifiers.mockResolvedValue([COST]);
    listBehaviorFindings.mockResolvedValue({ findings: [] });
    renderPage("clf-c");

    fireEvent.click(await within(await waitFor(() => section("Reset"))).findByRole("button", { name: "Reset classifier" }));
    expect(await screen.findByRole("dialog")).toBeTruthy();
  });
});
