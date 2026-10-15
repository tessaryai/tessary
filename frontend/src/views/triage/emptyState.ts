// SPDX-License-Identifier: Apache-2.0
// What Triage says when the queue is empty: four states ordered by how far a project has got, first
// match wins. Stopped traces and a missing provider key are modifiers, not states; only at the fourth
// state does the provider gap take the headline. Pure, so the whole table is cheap to test.
import type { TriageView } from "../../api/types";
import type { Onboarding } from "../onboarding/useOnboarding";
import { relativeTime } from "../../lib/relativeTime";

export type NodeTone =
  /** Reached and behind us. Dim: it is context, not the subject. */
  | "satisfied"
  /** The first stage that is not satisfied — what this screen is about. */
  | "focus"
  /** Something a reader should act on. The only place a hue appears on this screen. */
  | "warn"
  /** Not reached yet. Dashed, because it is an outline of a thing rather than a thing. */
  | "empty";

export interface PipelineNode {
  label: string;
  value: string;
  sub: string;
  tone: NodeTone;
  /** 0..1, drawn as a bar under the value. Only the findings node during baseline fitting. */
  progress?: number;
}

export type Action =
  | { kind: "link"; label: string; to: string }
  /** Copies the OTLP endpoint. The renderer owns the URL, since it is a property of the browser. */
  | { kind: "copyEndpoint"; label: string };

export interface EmptyState {
  /** Which branch fired. Not rendered — this exists so tests can assert the branch, not the prose. */
  key: "no-classifiers" | "fitting" | "no-findings" | "no-cases" | "no-provider";
  title: string;
  body: string;
  primary: Action;
  secondary?: Action;
  /** The staleness modifier's line, in the warning color. Present iff traces have stopped. */
  note?: string;
  nodes: [PipelineNode, PipelineNode, PipelineNode];
}

/**
 * What the screen knows about the org's provider keys. `configured` is null while the read is in flight,
 * which must never fire the modifier. Without `canConfigure` the Providers route redirects back here, so
 * the screen names the fix instead of linking to it.
 */
export interface ProviderFacts {
  /** How many providers the ORG holds a key for, or null while the read is in flight. */
  configured: number | null;
  /** Whether this org may add one. Fail-closed: false while the capability read is in flight. */
  canConfigure: boolean;
}

/** "3 classifiers", "1 call site" — the copy is prose, so its counts have to be. */
function plural(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? "" : "s"}`;
}

/** Counts are never rounded or abbreviated: 12,481 is a fact, "12k" is a summary of one. */
function count(n: number): string {
  return n.toLocaleString();
}

export function resolveState(
  watching: TriageView["watching"],
  onboarding: Onboarding,
  basePath: string,
  providers: ProviderFacts,
): EmptyState {
  const traces = watching.traces_total ?? 0;
  const live = watching.open_findings ?? 0;
  const classifiers = watching.classifiers;
  const callSites = watching.call_sites;

  // Reads the 24h window, the one number here that answers "is the exporter alive".
  const stale = watching.traces_last_day === 0;
  const tracesTo = `${basePath}/traces`;
  const classifiersTo = `${basePath}/classifiers`;

  const lastTrace = onboarding.data?.last_trace_at ?? null;
  const tracesNode = (tone: NodeTone): PipelineNode => ({
    label: "Traces",
    value: count(traces),
    // With no trace ever recorded (the sample project skips the connect gate) there is no recency to state.
    sub: stale
      ? lastTrace
        ? `last trace ${relativeTime(lastTrace)}`
        : "no trace recorded yet"
      : plural(callSites, "call site"),
    tone: stale ? "warn" : tone,
  });
  const casesNode: PipelineNode = {
    label: "Cases open",
    value: "0",
    sub: "nothing validated yet",
    tone: "empty",
  };

  const state = ((): EmptyState => {
    // 1. Nothing is evaluating the traffic. A classifier can be disabled and forgotten, so zero is real.
    if (classifiers === 0) {
      return {
        key: "no-classifiers",
        title: "No classifiers running",
        body: `Tessary has received ${count(traces)} traces. No classifier is evaluating them yet.`,
        primary: { kind: "link", label: "View classifiers", to: classifiersTo },
        secondary: { kind: "link", label: "View traces", to: tracesTo },
        nodes: [
          tracesNode("satisfied"),
          {
            label: "Findings open",
            value: "0",
            sub: "nothing is evaluating traces",
            tone: "warn",
          },
          casesNode,
        ],
      };
    }

    // 2. Classifiers exist but have no baseline yet, which is why a new project is not told all is well.
    if (onboarding.stage === "fitting") {
      const armed = onboarding.data?.baseline_buckets_armed ?? 0;
      const buckets = onboarding.data?.baseline_buckets ?? 0;
      const mechanism = "Classifiers compare recent traffic against the traffic before it.";
      return {
        key: "fitting",
        title: "Baselines are still fitting",
        body:
          buckets === 0
            ? `${mechanism} No windows have opened yet.`
            : `${mechanism} ${armed} of ${plural(buckets, "window")} ${armed === 1 ? "holds" : "hold"} enough comparable traces.`,
        primary: { kind: "link", label: "View traces", to: tracesTo },
        nodes: [
          tracesNode("satisfied"),
          {
            label: "Findings open",
            value: `${armed} / ${buckets}`,
            sub: "windows ready",
            tone: "focus",
            progress: onboarding.fittingProgress ?? 0,
          },
          casesNode,
        ],
      };
    }

    // 3. Watching, and nothing has moved: the only genuine all-clear.
    if (live === 0) {
      return {
        key: "no-findings",
        title: "Nothing to review",
        body: `${plural(classifiers, "classifier")} ${classifiers === 1 ? "has" : "have"} evaluated ${count(traces)} traces and ${classifiers === 1 ? "hasn't" : "haven't"} created a finding.`,
        primary: { kind: "link", label: "View traces", to: tracesTo },
        secondary: { kind: "link", label: "View classifiers", to: classifiersTo },
        nodes: [
          tracesNode("focus"),
          { label: "Findings open", value: "0", sub: "no condition met", tone: "empty" },
          casesNode,
        ],
      };
    }

    // 4. Findings exist and none became a case. They are listed below Cases, so there is no "review" link.
    return {
      key: "no-cases",
      title: "No open cases",
      body: `${plural(live, "finding")} ${live === 1 ? "is" : "are"} open. Triage hasn't determined that ${live === 1 ? "it is" : live === 2 ? "either one is" : "any of them are"} a real issue.`,
      primary: { kind: "link", label: "View traces", to: tracesTo },
      nodes: [
        tracesNode("satisfied"),
        {
          label: "Findings open",
          value: count(live),
          sub: `${plural(classifiers, "classifier")} running`,
          tone: "focus",
        },
        casesNode,
      ],
    };
  })();

  const withProviders = applyProviderGap(state, providers, live, basePath, tracesTo);

  if (!stale) return withProviders;
  // The staleness modifier, applied last so no branch forgets it, replaces the secondary action. With both
  // modifiers live the exporter takes the one note slot; the provider gap keeps its headline and action.
  return {
    ...withProviders,
    note: "No traces have arrived in the last 24 hours. Check that your exporter is still sending.",
    secondary: { kind: "copyEndpoint", label: "Copy endpoint" },
  };
}

/**
 * The org holds no provider key, so triage has nothing to run on. At `no-classifiers`/`fitting` it is a
 * note; at `no-findings` the screen offers the key; at `no-cases` it takes the headline, because triage
 * never ran. Without the capability the screen states the fact and leaves the existing action alone.
 */
function applyProviderGap(
  state: EmptyState,
  providers: ProviderFacts,
  live: number,
  basePath: string,
  tracesTo: string,
): EmptyState {
  if (providers.configured !== 0) return state;

  const addKey: Action = { kind: "link", label: "Add a provider key", to: `${basePath}/settings/providers` };
  const shared = "Provider keys are shared by every project in this organization.";
  const blocked: PipelineNode = { label: "Cases open", value: "0", sub: "triage cannot run", tone: "warn" };

  // 1-2. Another stage owns the screen. State the fact and get out of its way.
  if (state.key === "no-classifiers" || state.key === "fitting") {
    return {
      ...state,
      note: "No provider key is configured. Triage needs one before it can review a finding.",
    };
  }

  // 3. Nothing is blocked yet, so "Nothing to review" is still true and keeps the headline.
  if (state.key === "no-findings") {
    return {
      ...state,
      note: "No provider key is configured. Triage cannot review a finding until one is added.",
      primary: providers.canConfigure ? addKey : state.primary,
      secondary: providers.canConfigure
        ? { kind: "link", label: "View traces", to: tracesTo }
        : state.secondary,
      nodes: [state.nodes[0], state.nodes[1], blocked],
    };
  }

  // 4. A finding is blocked. The gap becomes the subject of the screen.
  const findings = live === 1 ? "the finding" : "them";
  return {
    ...state,
    key: "no-provider",
    title: "No provider key configured",
    body: providers.canConfigure
      ? `${plural(live, "finding")} ${live === 1 ? "is" : "are"} open. Triage uses a model you provide, so it cannot review ${findings} until you add a key.`
      : `${plural(live, "finding")} ${live === 1 ? "is" : "are"} open. Triage uses a model you provide, and this organization has no provider key.`,
    note: shared,
    primary: providers.canConfigure ? addKey : state.primary,
    secondary: providers.canConfigure ? { kind: "link", label: "View traces", to: tracesTo } : state.secondary,
    nodes: [state.nodes[0], state.nodes[1], blocked],
  };
}

/**
 * Whether the empty queue needs the setup screen rather than one "Nothing needs you." line. Only findings no
 * case came from can be a line, since Triage lists them below Cases; any note also needs the screen.
 */
export function needsSetupScreen(state: EmptyState): boolean {
  return state.key !== "no-cases" || state.note != null;
}
