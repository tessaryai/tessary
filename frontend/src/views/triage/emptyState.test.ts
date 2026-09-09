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
import { resolveState } from "./emptyState";
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
    stageIndex: 3,
    warmingUp: true,
    fittingProgress: 0.25,
    isLoading: false,
    error: null,
    ...over,
  };
}

describe("resolveState", () => {
  it("names the missing classifiers before anything downstream of them", () => {
    const s = resolveState(watching({ classifiers: 0 }), onboarding(), BASE, HAS_PROVIDER);
    expect(s.key).toBe("no-classifiers");
    expect(s.title).toBe("No classifiers running");
    expect(s.body).toBe("Tessary has received 12,481 traces. No classifier is evaluating them yet.");
    expect(s.primary).toEqual({ kind: "link", label: "View classifiers", to: `${BASE}/classifiers` });
    // The findings node carries the warning, because it is the stage that is not happening.
    expect(s.nodes[1].tone).toBe("warn");
  });

  it("prefers the missing classifiers over the absent findings they would have created", () => {
    // Both conditions are true. Saying "nothing to review" here would be accurate and useless.
    const s = resolveState(watching({ classifiers: 0, open_findings: 0 }), onboarding(), BASE, HAS_PROVIDER);
    expect(s.key).toBe("no-classifiers");
  });

  it("says baselines are fitting, with the windows that are ready", () => {
    const s = resolveState(watching(), onboarding({ stage: "fitting" }), BASE, HAS_PROVIDER);
    expect(s.key).toBe("fitting");
    expect(s.title).toBe("Baselines are still fitting");
    expect(s.body).toContain("1 of 4 windows holds enough comparable traces.");
    expect(s.nodes[1].value).toBe("1 / 4");
    expect(s.nodes[1].progress).toBe(0.25);
  });

  it("does not print a window count before any window has opened", () => {
    const o = onboarding({ stage: "fitting" });
    const s = resolveState(
      watching(),
      { ...o, data: { ...o.data!, baseline_buckets: 0, baseline_buckets_armed: 0 } },
      BASE,
      HAS_PROVIDER,
    );
    expect(s.body).toContain("No windows have opened yet.");
    expect(s.body).not.toContain("0 of 0");
  });

  it("claims the all-clear only by naming what did the watching", () => {
    const s = resolveState(watching({ open_findings: 0 }), onboarding(), BASE, HAS_PROVIDER);
    expect(s.key).toBe("no-findings");
    expect(s.title).toBe("Nothing to review");
    expect(s.body).toBe(
      "3 classifiers have evaluated 12,481 traces and haven't created a finding.",
    );
    expect(s.nodes[0].tone).toBe("focus");
  });

  it("sends open findings to the classifiers list, counted", () => {
    const s = resolveState(watching({ open_findings: 2 }), onboarding(), BASE, HAS_PROVIDER);
    expect(s.key).toBe("no-cases");
    expect(s.title).toBe("No open cases");
    expect(s.body).toBe(
      "2 findings are open. Triage hasn't determined that either one is a real issue.",
    );
    expect(s.primary).toEqual({ kind: "link", label: "Review 2 findings", to: `${BASE}/classifiers` });
  });

  it("drops the plural at one finding, in the label and the prose", () => {
    const s = resolveState(watching({ open_findings: 1 }), onboarding(), BASE, HAS_PROVIDER);
    expect(s.primary).toMatchObject({ label: "Review 1 finding" });
    expect(s.body).toBe("1 finding is open. Triage hasn't determined that it is a real issue.");
  });

  it("stops saying 'either one' past two findings", () => {
    const s = resolveState(watching({ open_findings: 5 }), onboarding(), BASE, HAS_PROVIDER);
    expect(s.body).toBe(
      "5 findings are open. Triage hasn't determined that any of them are a real issue.",
    );
  });

  it("never abbreviates a count", () => {
    const s = resolveState(watching({ traces_total: 4_812_003, open_findings: 0 }), onboarding(), BASE, HAS_PROVIDER);
    // Grouped in the runner's locale, not hard-coded: `count` formats with toLocaleString, so an
    // en-IN machine groups this 48,12,003 and an en-US one 4,812,003. Both are the full number,
    // which is what this pins — an abbreviation ("4.8M") contains neither.
    expect(s.body).toContain((4_812_003).toLocaleString());
    expect(s.body).not.toMatch(/\d[\d,.\u00a0\u202f]*\s?[kKmM]\b/);
  });

  describe("the stopped-exporter modifier", () => {
    it("warns on the traces node and adds a line without touching the headline", () => {
      const live = resolveState(watching({ open_findings: 2 }), onboarding(), BASE, HAS_PROVIDER);
      const stale = resolveState(
        watching({ open_findings: 2, traces_last_day: 0 }),
        onboarding(),
        BASE,
        HAS_PROVIDER,
      );
      expect(stale.title).toBe(live.title);
      expect(stale.body).toBe(live.body);
      expect(stale.primary).toEqual(live.primary);
      expect(stale.note).toBe(
        "No traces have arrived in the last 24 hours. Check that your exporter is still sending.",
      );
      expect(stale.nodes[0].tone).toBe("warn");
    });

    it("still prints the traces the project has, rather than denying them", () => {
      // The bug this whole screen replaced: a headline of "Nothing is arriving." over 12,481 traces.
      const s = resolveState(watching({ traces_last_day: 0 }), onboarding(), BASE, HAS_PROVIDER);
      expect(s.nodes[0].value).toBe((12_481).toLocaleString());
      expect(s.nodes[0].sub).toMatch(/^last trace \d+d ago$/);
      expect(s.title).not.toMatch(/no traces/i);
    });

    it("replaces the second action rather than offering a third", () => {
      const s = resolveState(watching({ traces_last_day: 0, open_findings: 2 }), onboarding(), BASE, HAS_PROVIDER);
      expect(s.secondary).toEqual({ kind: "copyEndpoint", label: "Copy endpoint" });
    });

    it("applies to every state, including the ones that never mention traces", () => {
      for (const [w, o] of [
        [watching({ classifiers: 0, traces_last_day: 0 }), onboarding()],
        [watching({ traces_last_day: 0 }), onboarding({ stage: "fitting" })],
        [watching({ traces_last_day: 0, open_findings: 0 }), onboarding()],
      ] as const) {
        expect(resolveState(w, o, BASE, HAS_PROVIDER).note).toBeDefined();
      }
    });
  });

  describe("the missing-provider modifier", () => {
    const NONE = { configured: 0, canConfigure: true };
    const NONE_GATED = { configured: 0, canConfigure: false };
    const KEY_NOTE = "No provider key is configured. Triage needs one before it can review a finding.";

    describe("while another stage still owns the screen", () => {
      it("adds the note and changes nothing else", () => {
        for (const [w, o] of [
          [watching({ classifiers: 0 }), onboarding()],
          [watching(), onboarding({ stage: "fitting" })],
        ] as const) {
          const base = resolveState(w, o, BASE, HAS_PROVIDER);
          const gap = resolveState(w, o, BASE, NONE);
          expect(gap.note).toBe(KEY_NOTE);
          expect({ ...gap, note: undefined }).toEqual({ ...base, note: undefined });
        }
      });
    });

    describe("at nothing-to-review, where no finding is blocked yet", () => {
      it("keeps the all-clear headline, since nothing has failed", () => {
        const s = resolveState(watching({ open_findings: 0 }), onboarding(), BASE, NONE);
        expect(s.key).toBe("no-findings");
        expect(s.title).toBe("Nothing to review");
        expect(s.body).toBe(
          "3 classifiers have evaluated 12,481 traces and haven't created a finding.",
        );
      });

      it("still offers the key and warns the stage that would fail", () => {
        const s = resolveState(watching({ open_findings: 0 }), onboarding(), BASE, NONE);
        expect(s.note).toBe(
          "No provider key is configured. Triage cannot review a finding until one is added.",
        );
        expect(s.primary).toEqual({
          kind: "link",
          label: "Add a provider key",
          to: `${BASE}/settings/providers`,
        });
        expect(s.secondary).toEqual({ kind: "link", label: "View traces", to: `${BASE}/traces` });
        expect(s.nodes[2]).toEqual({
          label: "Cases open",
          value: "0",
          sub: "triage cannot run",
          tone: "warn",
        });
      });
    });

    describe("at no-open-cases, where a finding is blocked", () => {
      it("takes the headline, the one place a modifier does", () => {
        const s = resolveState(watching({ open_findings: 1 }), onboarding(), BASE, NONE);
        expect(s.key).toBe("no-provider");
        expect(s.title).toBe("No provider key configured");
        expect(s.body).toBe(
          "1 finding is open. Triage uses a model you provide, so it cannot review the finding until you add a key.",
        );
      });

      it("stops claiming triage reached a judgment it never reached", () => {
        const s = resolveState(watching({ open_findings: 1 }), onboarding(), BASE, NONE);
        expect(s.body).not.toContain("hasn't determined");
      });

      it("switches to a plural object past one finding", () => {
        const s = resolveState(watching({ open_findings: 3 }), onboarding(), BASE, NONE);
        expect(s.body).toBe(
          "3 findings are open. Triage uses a model you provide, so it cannot review them until you add a key.",
        );
      });

      it("offers the key first and the findings second", () => {
        const s = resolveState(watching({ open_findings: 2 }), onboarding(), BASE, NONE);
        expect(s.primary).toEqual({
          kind: "link",
          label: "Add a provider key",
          to: `${BASE}/settings/providers`,
        });
        expect(s.secondary).toEqual({
          kind: "link",
          label: "Review 2 findings",
          to: `${BASE}/classifiers`,
        });
        expect(s.note).toBe("Provider keys are shared by every project in this organization.");
      });
    });

    describe("when the org cannot hold a key at all", () => {
      it("states the fact but never points at the route that would bounce it back", () => {
        const s = resolveState(watching({ open_findings: 2 }), onboarding(), BASE, NONE_GATED);
        expect(s.title).toBe("No provider key configured");
        expect(s.body).toBe(
          "2 findings are open. Triage uses a model you provide, and this organization has no provider key.",
        );
        expect(s.body).not.toContain("you add a key");
        expect(JSON.stringify(s)).not.toContain("settings/providers");
        expect(s.nodes[2].tone).toBe("warn");
      });

      it("withholds the offer at nothing-to-review too", () => {
        const s = resolveState(watching({ open_findings: 0 }), onboarding(), BASE, NONE_GATED);
        expect(JSON.stringify(s)).not.toContain("settings/providers");
        expect(s.note).toContain("No provider key is configured");
      });
    });

    it("stays quiet until the read settles, rather than accusing a project that has a key", () => {
      const unknown = resolveState(watching({ open_findings: 2 }), onboarding(), BASE, {
        configured: null,
        canConfigure: true,
      });
      expect(unknown).toEqual(
        resolveState(watching({ open_findings: 2 }), onboarding(), BASE, HAS_PROVIDER),
      );
      expect(unknown.body).toContain("hasn't determined");
    });

    it("yields the note line to a stopped exporter, and keeps everything else", () => {
      const s = resolveState(
        watching({ open_findings: 1, traces_last_day: 0 }),
        onboarding(),
        BASE,
        NONE,
      );
      expect(s.note).toMatch(/^No traces have arrived/);
      expect(s.title).toBe("No provider key configured");
      expect(s.primary).toMatchObject({ label: "Add a provider key" });
      expect(s.nodes[0].tone).toBe("warn");
      expect(s.nodes[2].tone).toBe("warn");
    });
  });
});
