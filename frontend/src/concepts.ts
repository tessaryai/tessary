// SPDX-License-Identifier: Apache-2.0
/*
 * Single source of truth for every domain noun the UI surfaces.
 * Mirrors the canonical nomenclature of the `evals` Claude Code plugin
 * (see tessaryai/plugins/plugins/evals/output_format.md). When the plugin's
 * vocabulary changes, update here and the platform follows.
 *
 * Components read this via <ConceptPopover concept="pipeline" />.
 */

export type ConceptId =
  | "pipeline"
  | "call_site"
  | "chain"
  | "failure_mode"
  | "invariant"
  | "pack"
  | "taxonomy"
  | "trace_source"
  | "severity";

export type Concept = {
  label: string;
  gloss: string;
  long?: string;
  learnMore?: string;
};

export const CONCEPTS: Record<ConceptId, Concept> = {
  pipeline: {
    label: "Pipeline",
    gloss: "The full set of call sites and failure modes synthesized from your repo.",
    long: "A pipeline is what the evals plugin emits as a .tessary/ bundle. It tells the platform what your product does, where its call sites live, and what could go wrong there.",
  },
  call_site: {
    label: "Call site",
    gloss: "A specific place in your code (or a trace) where your product calls an LLM.",
    long: "Each LLM call has a shape (for example classify, summarize, or extract), an intent, and a set of failure modes the synthesizer believes can occur there. Chains are multi-step LLM calls that span more than one site.",
  },
  chain: {
    label: "Chain",
    gloss: "A multi-call flow that spans more than one LLM call, for example retrieve, then answer, then cite.",
    long: "Chains exist because some failures only show up when calls interact. Looking at a single step can't show the full handoff.",
  },
  failure_mode: {
    label: "Failure mode",
    gloss: "A concrete way an LLM call can fail: a hallucinated citation, a refusal on a valid query, leaked PII (personally identifiable information), and so on.",
    long: "Failure modes are organized by pack (quality / security / reliability / brand) and by severity (high / medium / low).",
  },
  invariant: {
    label: "Invariant",
    gloss: "A guarantee your product is supposed to hold across every LLM call, for example \"never reveal the system prompt\".",
    long: "Inferred from your code. Each one tracks which LLM calls enforce it and which are likely gaps.",
  },
  pack: {
    label: "Pack",
    gloss: "A bundle of failure modes grouped by concern: quality, security, reliability, or brand.",
    long: "Packs engage automatically based on what the synthesizer sees in your repo. For example, the brand pack engages when your product has user-facing copy.",
  },
  taxonomy: {
    label: "Taxonomy",
    gloss: "The hierarchical tree of failure modes for your product, organized for navigation.",
  },
  trace_source: {
    label: "Source",
    gloss: "A connection to an observability vendor (Braintrust, Langfuse) that supplies real spans.",
  },
  severity: {
    label: "Severity",
    gloss: "How bad a failure of this kind is: high (block), medium (warn), or low (report).",
  },
};
