// SPDX-License-Identifier: Apache-2.0
// Every domain noun the UI surfaces, mirroring the `evals` plugin's nomenclature
// (tessaryai/plugins/plugins/evals/output_format.md).

export type ConceptId = "pipeline" | "call_site" | "trace_source";

export type Concept = {
  label: string;
  gloss: string;
  long?: string;
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
  trace_source: {
    label: "Source",
    gloss: "A connection to an observability vendor (Braintrust, Langfuse) that supplies real spans.",
  },
};
