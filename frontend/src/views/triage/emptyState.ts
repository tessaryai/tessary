// SPDX-License-Identifier: Apache-2.0
/*
 * What Triage says when the queue is empty.
 *
 * An empty queue is not one state, it is four, and the screen this replaced could tell apart only
 * two of them — it branched on `traces_last_day === 0` and swapped the whole headline to "Nothing is
 * arriving." Neither branch offered anywhere to go, and the silent one told a project with months of
 * history that nothing had ever arrived.
 *
 * The four are ordered by how far a project has got, and the FIRST match wins: a project with no
 * classifiers has no findings either, and saying "nothing to review" to it would be true and useless.
 * Whichever stage is the first one not satisfied is the stage the screen is about, and the primary
 * action goes there — so the branch table and the pipeline drawn above it are the same fact twice,
 * and neither can drift from the other.
 *
 * Traces stopping is deliberately NOT a fifth state. It can be true underneath any of the four, so
 * it modifies the traces node and adds a line; it never takes the headline. That is what keeps the
 * headline from contradicting the trace count sitting beside it.
 *
 * A missing model provider key is the SECOND modifier, and it is the one exception to the sentence
 * above: at the FOURTH state it does take the headline. It earns that, and only there. Everywhere
 * else a stage still owns the screen and has its own next step, so the key gets the note line and
 * nothing more — there is no point asking for a key before a finding exists to be blocked by it.
 * At the fourth state a finding IS blocked, nothing else is the subject, and the old headline was
 * describing a judgment triage never made.
 *
 * Pure on purpose — two payloads in, a description out, no rendering and no clock beyond the one
 * `relativeTime` reads. That is what makes the whole table cheap to test.
 */
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
 * What the screen knows about the org's model provider keys.
 *
 * `configured` is nullable on purpose. `null` means the read has not settled, and an unsettled read
 * must never fire the modifier: telling a project that has a key it has none, for the half second
 * before the read lands, is a worse failure than being a beat late to say it.
 *
 * `canConfigure` is the `byo_provider_keys_enabled` capability, and it decides whether this screen
 * offers the fix or only names it. The capability gates the Providers route itself — without it,
 * that route redirects straight back to this one — so a CTA pointing there would be a loop.
 */
export interface ProviderFacts {
  /** How many providers the ORG holds a key for, or null while the read is in flight. */
  configured: number | null;
  /** Whether this org may add one. Fail-closed: false while the capability read is in flight. */
  canConfigure: boolean;
}

/** "3 classifiers", "1 call site" — the copy is prose, so its counts have to be. */
export function plural(n: number, noun: string): string {
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

  // Traces stopping is the modifier. It reads the 24h window — the one number on this payload that
  // exists to answer "is the exporter alive", and the one this screen must never print on its own.
  const stale = watching.traces_last_day === 0;
  const tracesTo = `${basePath}/traces`;
  const classifiersTo = `${basePath}/classifiers`;

  const lastTrace = onboarding.data?.last_trace_at ?? null;
  const tracesNode = (tone: NodeTone): PipelineNode => ({
    label: "Traces",
    value: count(traces),
    // Under the modifier the sub-label carries the recency, since that is the fact the reader needs
    // and the one the value deliberately does not encode. With no trace ever recorded there is no
    // recency to state — the sample project reaches this screen without the connect gate — so say
    // that instead of rendering "last trace —".
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
    // 1. Nothing is evaluating the traffic. First, because every stage after it is downstream of a
    //    classifier existing — and a classifier can be disabled and forgotten, so zero is a real
    //    thing to say out loud rather than an impossible state.
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

    // 2. Classifiers exist but cannot compare yet. Not an absence of findings — an absence of the
    //    baseline a finding would be measured against, which is a different sentence and the whole
    //    reason a new project is not told that all is well.
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

    // 3. Watching, and nothing has moved. The only one of the four that is genuinely an all-clear,
    //    and it earns the claim by naming what did the watching.
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

    // 4. Findings exist and none of them became a case. The distinction the glossary turns on:
    //    triage is what decides a finding is a real issue, and it has not.
    return {
      key: "no-cases",
      title: "No open cases",
      body: `${plural(live, "finding")} ${live === 1 ? "is" : "are"} open. Triage hasn't determined that ${live === 1 ? "it is" : live === 2 ? "either one is" : "any of them are"} a real issue.`,
      primary: { kind: "link", label: `Review ${plural(live, "finding")}`, to: classifiersTo },
      secondary: { kind: "link", label: "View traces", to: tracesTo },
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

  const withProviders = applyProviderGap(state, providers, live, basePath, classifiersTo, tracesTo);

  if (!stale) return withProviders;
  // The modifier, applied last so it cannot be forgotten in a branch. It replaces the secondary
  // rather than adding a third action: the reader is being asked to go and check one thing, and a
  // screen offering three ways out is not asking anything.
  //
  // Applied last also settles what happens when BOTH modifiers are live: there is one note slot and
  // the exporter takes it, because a stopped exporter is the thing to go and check first. The
  // provider gap keeps its headline, its cases node and its primary action, so neither fact is lost.
  return {
    ...withProviders,
    note: "No traces have arrived in the last 24 hours. Check that your exporter is still sending.",
    secondary: { kind: "copyEndpoint", label: "Copy endpoint" },
  };
}

/**
 * The second modifier: the org holds no model provider key, so triage has nothing to run on.
 *
 * It applies at three strengths, and which one fires is decided by how far the project has got —
 * the same ordering the branch table above uses, for the same reason.
 *
 *   1-2. `no-classifiers` / `fitting` — a stage still owns the screen and has its own next step, and
 *        a key cannot block anything yet because no finding exists. The note line, and nothing else.
 *   3.   `no-findings` — still nothing blocked, so the all-clear headline stands. But this is the
 *        last moment before a finding arrives, so the screen offers the key and warns the cases node.
 *   4.   `no-cases` — a finding IS blocked. Here the gap TAKES the headline, the one place in this
 *        file where a modifier does. It earns it twice over: nothing else is the subject, and the
 *        headline it replaces was describing a judgment triage never made. The old body said triage
 *        "hasn't determined that it is a real issue", which reads as a verdict. Triage never ran —
 *        the run throws on the missing credential before it reaches a model.
 *
 * Every strength splits on the capability. With it the screen offers the fix; without it the org
 * cannot hold a key at all, so the screen states the fact and leaves the existing action alone
 * rather than pointing at a route that would redirect straight back here.
 */
function applyProviderGap(
  state: EmptyState,
  providers: ProviderFacts,
  live: number,
  basePath: string,
  classifiersTo: string,
  tracesTo: string,
): EmptyState {
  if (providers.configured !== 0) return state;

  const addKey: Action = { kind: "link", label: "Add a provider key", to: `${basePath}/settings/providers` };
  // Where the key lives, which no button label has room to say. It is also the answer to the
  // question an engineer who cannot add one asks next: this is not a setting on their project.
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
    secondary: providers.canConfigure
      ? { kind: "link", label: `Review ${plural(live, "finding")}`, to: classifiersTo }
      : state.secondary,
    nodes: [state.nodes[0], state.nodes[1], blocked],
  };
}
