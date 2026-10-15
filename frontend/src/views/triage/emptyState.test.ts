// SPDX-License-Identifier: Apache-2.0
/*
 * The empty-queue branch table, proved directly.
 *
 * `resolveState` is a pure function of two payloads, so every state, the modifier over each of them,
 * and the count-dependent prose are all cheaper to assert here than by driving a stack into four
 * different shapes. What this suite is actually protecting is the two properties the screen is built
 * on, both of which are easy to break in a plausible-looking edit:
 *
 *   - FIRST match wins, in stage order. A project with no classifiers also has no findings, and the
 *     screen must say the first thing, not the second.
 *   - The modifier never takes the headline. That is the whole reason the old screen was wrong.
 */
import { describe, expect, it } from "vitest";
import { needsSetupScreen, resolveState } from "./emptyState";
import type { Onboarding } from "../onboarding/useOnboarding";
import type { TriageView } from "../../api/types";

const BASE = "/orgs/acme/projects/web";

/*
 * The default for every case that is not about providers: one key, and the org may hold one. Spelled
 * out rather than defaulted inside `resolveState`, because the whole point of the provider modifier
 * is that an UNKNOWN answer and a ZERO answer are different, and a parameter with a default would
 * let a call site forget the difference silently.
 */
const HAS_PROVIDER = { configured: 1, canConfigure: true };

function watching(over: Partial<TriageView["watching"]> = {}): TriageView["watching"] {
  return {
    classifiers: 3,
    call_sites: 4,
    traces_last_day: 900,
    traces_total: 12_481,
    open_findings: 0,
    ...over,
  };
}

function onboarding(over: Partial<Onboarding> = {}): Onboarding {
  return {
    data: {
      stage: "watching",
      listening: true,
      cases: 0,
      findings: 0,
      triaged_cases: 0,
      first_trace_at: null,
      last_trace_at: "2026-09-04T10:00:00Z",
      first_finding_at: null,
      first_triaged_case_at: null,
      baseline_buckets: 4,
      baseline_buckets_armed: 1,
      baseline_min_sample: 100,
      baseline_samples_in_flight: 40,
      baseline_best_window_count: 25,
    },
    stage: "watching",
    fittingProgress: 0.25,
    ...over,
  };
}

const NONE = { configured: 0, canConfigure: true };
const NONE_GATED = { configured: 0, canConfigure: false };
const KEY_NOTE = "No provider key is configured. Triage needs one before it can review a finding.";
const TRACES = { kind: "link", label: "View traces", to: `${BASE}/traces` };
const ADD_KEY = { kind: "link", label: "Add a provider key", to: `${BASE}/settings/providers` };
const ALL_CLEAR = "3 classifiers have evaluated 12,481 traces and haven't created a finding.";
const resolve = (w: Partial<TriageView["watching"]> = {}, o: Partial<Onboarding> = {}, p: Provider = HAS_PROVIDER) =>
  resolveState(watching(w), onboarding(o), BASE, p);
type Provider = Parameters<typeof resolveState>[3];

describe("resolveState", () => {
  it.each([
    [
      "names the missing classifiers before anything downstream of them, warning on findings",
      { classifiers: 0 }, {}, HAS_PROVIDER,
      {
        key: "no-classifiers",
        title: "No classifiers running",
        body: "Tessary has received 12,481 traces. No classifier is evaluating them yet.",
        primary: { kind: "link", label: "View classifiers", to: `${BASE}/classifiers` },
        nodes: [{}, { tone: "warn" }, {}],
      },
    ],
    [
      "says baselines are fitting, with the windows that are ready",
      {}, { stage: "fitting" }, HAS_PROVIDER,
      { key: "fitting", title: "Baselines are still fitting", nodes: [{}, { value: "1 / 4", progress: 0.25 }, {}] },
    ],
    [
      "claims the all-clear only by naming what did the watching",
      { open_findings: 0 }, {}, HAS_PROVIDER,
      { key: "no-findings", title: "Nothing to review", body: ALL_CLEAR, nodes: [{ tone: "focus" }, {}, {}] },
    ],
    // Bug: the open findings sent to the Classifiers page, which no longer lists them.
    [
      "counts the open findings, and sends the reader to traces rather than to the Classifiers page",
      { open_findings: 2 }, {}, HAS_PROVIDER,
      {
        key: "no-cases",
        title: "No open cases",
        body: "2 findings are open. Triage hasn't determined that either one is a real issue.",
        primary: TRACES,
      },
    ],
    [
      "drops the plural at one finding",
      { open_findings: 1 }, {}, HAS_PROVIDER,
      { body: "1 finding is open. Triage hasn't determined that it is a real issue." },
    ],
    [
      "stops saying 'either one' past two findings",
      { open_findings: 5 }, {}, HAS_PROVIDER,
      { body: "5 findings are open. Triage hasn't determined that any of them are a real issue." },
    ],
    [
      "replaces the second action with the endpoint when the exporter stopped, rather than offering a third",
      { traces_last_day: 0, open_findings: 2 }, {}, HAS_PROVIDER,
      { secondary: { kind: "copyEndpoint", label: "Copy endpoint" } },
    ],
    [
      "keeps the all-clear headline without a provider key, since nothing has failed, but offers the key",
      { open_findings: 0 }, {}, NONE,
      {
        key: "no-findings",
        title: "Nothing to review",
        body: ALL_CLEAR,
        note: "No provider key is configured. Triage cannot review a finding until one is added.",
        primary: ADD_KEY,
        secondary: TRACES,
        nodes: [{}, {}, { label: "Cases open", value: "0", sub: "triage cannot run", tone: "warn" }],
      },
    ],
    [
      "lets a missing key take the headline at one blocked finding, the one place a modifier does",
      { open_findings: 1 }, {}, NONE,
      {
        key: "no-provider",
        title: "No provider key configured",
        body: "1 finding is open. Triage uses a model you provide, so it cannot review the finding until you add a key.",
      },
    ],
    [
      "switches to a plural object past one blocked finding",
      { open_findings: 3 }, {}, NONE,
      { body: "3 findings are open. Triage uses a model you provide, so it cannot review them until you add a key." },
    ],
    [
      "offers the key first and traces second, never the Classifiers page the findings left",
      { open_findings: 2 }, {}, NONE,
      { primary: ADD_KEY, secondary: TRACES, note: "Provider keys are shared by every project in this organization." },
    ],
    [
      "states that the org has no key without pointing at the route that would bounce it back",
      { open_findings: 2 }, {}, NONE_GATED,
      {
        title: "No provider key configured",
        body: "2 findings are open. Triage uses a model you provide, and this organization has no provider key.",
        nodes: [{}, {}, { tone: "warn" }],
      },
    ],
    [
      "yields the note line to a stopped exporter, and keeps the missing key's headline and warnings",
      { open_findings: 1, traces_last_day: 0 }, {}, NONE,
      {
        note: expect.stringMatching(/^No traces have arrived/),
        title: "No provider key configured",
        primary: { label: "Add a provider key" },
        nodes: [{ tone: "warn" }, {}, { tone: "warn" }],
      },
    ],
  ] as const)("%s", (_bug, w, o, p, expected) => {
    expect(resolve(w, o, p)).toMatchObject(expected);
  });

  it("prints the windows that are ready, or none before any window has opened", () => {
    expect(resolve({}, { stage: "fitting" }).body).toContain("1 of 4 windows holds enough comparable traces.");
    const o = onboarding({ stage: "fitting" });
    const s = resolveState(watching(), { ...o, data: { ...o.data!, baseline_buckets: 0, baseline_buckets_armed: 0 } }, BASE, HAS_PROVIDER);
    expect(s.body).toContain("No windows have opened yet.");
    expect(s.body).not.toContain("0 of 0");
  });

  it("offers no second action beside the traces link at no-open-cases", () => {
    expect(resolve({ open_findings: 2 }).secondary).toBeUndefined();
  });

  it("never claims a judgment triage could not reach, nor offers a key route the org would bounce", () => {
    expect(resolve({ open_findings: 1 }, {}, NONE).body).not.toContain("hasn't determined");
    expect(resolve({ open_findings: 2 }, {}, NONE_GATED).body).not.toContain("you add a key");
    for (const open_findings of [0, 2]) expect(JSON.stringify(resolve({ open_findings }, {}, NONE_GATED))).not.toContain("settings/providers");
    expect(resolve({ open_findings: 0 }, {}, NONE_GATED).note).toContain("No provider key is configured");
  });

  // Bug: an all-clear line drawn over a state that still has a step to take, or over an empty queue whose only
  // proof of watching is the pipeline; or the setup screen pushed over open findings Triage lists below Cases.
  it("asks for the setup screen until a finding is open, and after that only where there is a step to take", () => {
    const at = (w: Partial<TriageView["watching"]>, o: Partial<Onboarding> = {}, p = HAS_PROVIDER) => needsSetupScreen(resolve(w, o, p));
    expect(at({ open_findings: 0 })).toBe(true);
    expect(at({ open_findings: 2 })).toBe(false);
    expect(at({ classifiers: 0 })).toBe(true);
    expect(at({}, { stage: "fitting" })).toBe(true);
    expect(at({ open_findings: 2, traces_last_day: 0 })).toBe(true);
    expect(at({ open_findings: 0 }, {}, NONE)).toBe(true);
    expect(at({ open_findings: 2 }, {}, NONE)).toBe(true);
  });

  describe("the stopped-exporter modifier", () => {
    it("warns on the traces node and adds a line without touching the headline", () => {
      const live = resolve({ open_findings: 2 });
      const stale = resolve({ open_findings: 2, traces_last_day: 0 });
      expect(stale).toMatchObject({ title: live.title, body: live.body, primary: live.primary, nodes: [{ tone: "warn" }, {}, {}] });
      expect(stale.note).toBe("No traces have arrived in the last 24 hours. Check that your exporter is still sending.");
    });

    it("still prints the traces the project has, rather than denying them", () => {
      // The bug this whole screen replaced: a headline of "Nothing is arriving." over 12,481 traces.
      const s = resolve({ traces_last_day: 0 });
      expect(s.nodes[0].value).toBe((12_481).toLocaleString());
      expect(s.nodes[0].sub).toMatch(/^last trace \d+d ago$/);
      expect(s.title).not.toMatch(/no traces/i);
    });

    it("applies to every state, including the ones that never mention traces", () => {
      expect(resolve({ classifiers: 0, traces_last_day: 0 }).note).toBeDefined();
      expect(resolve({ traces_last_day: 0 }, { stage: "fitting" }).note).toBeDefined();
      expect(resolve({ traces_last_day: 0, open_findings: 0 }).note).toBeDefined();
    });
  });

  describe("the missing-provider modifier", () => {
    it("adds the note and changes nothing else while another stage still owns the screen", () => {
      for (const [w, o] of [[{ classifiers: 0 }, {}], [{}, { stage: "fitting" as const }]] as const) {
        const gap = resolve(w, o, NONE);
        expect(gap.note).toBe(KEY_NOTE);
        expect({ ...gap, note: undefined }).toEqual({ ...resolve(w, o), note: undefined });
      }
    });

    it("stays quiet until the read settles, rather than accusing a project that has a key", () => {
      const unknown = resolve({ open_findings: 2 }, {}, { configured: null, canConfigure: true });
      expect(unknown).toEqual(resolve({ open_findings: 2 }));
      expect(unknown.body).toContain("hasn't determined");
    });
  });
});
