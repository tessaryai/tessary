// SPDX-License-Identifier: Apache-2.0
/*
 * A groundedness finding and the case it opened, as the API returns them: shared by the finding page's,
 * the case page's and the route smoke test's groundedness renders. Test-only; nothing in the app imports it.
 */
import type { BehaviorFindingDetail, CaseDetail, FlaggedAnswer, GroundednessDetail, RcaReport } from "../api/types";

const ANSWER =
  "Yes. Refunds are available for up to 60 days after purchase. " +
  "After 60 days, you can still get half of your payment back. Refunds go back to your original payment method.";

function sentence(text: string, score: number) {
  const start = ANSWER.indexOf(text);
  return { start, end: start + text.length, score };
}

export const FLAGGED_ANSWER: FlaggedAnswer = {
  traceId: "4f1c9a07e2b84d0f",
  spanId: "span-1",
  sessionId: "sess-1",
  flaggedAt: "2026-09-23T14:41:00Z",
  score: 0.99,
  question: "Can I get a refund after 30 days?",
  answer: ANSWER,
  flaggedSentences: [
    sentence("Refunds are available for up to 60 days after purchase.", 0.99),
    sentence("After 60 days, you can still get half of your payment back.", 0.98),
  ],
  documents: [
    {
      title: null,
      text: "Refunds are available within 30 days of purchase. After 30 days, we offer store credit.",
    },
  ],
  premiseHadEvidence: true,
  stored: true,
  cleared: false,
};

export const GROUNDEDNESS_DETAIL: GroundednessDetail = {
  rate: {
    bucketKey: "support-agent",
    criticality: 1,
    curRate: 0.064,
    refRate: 0.021,
    deltaPp: 4.3,
    direction: "up",
    effectSize: 0.2,
    failingTraces: [],
    failuresCur: 58,
    nCur: 906,
    nRef: 1000,
    onsetAt: "2026-09-23T09:12:00Z",
    patterns: [],
    patternsTruncated: false,
    statistic: 12,
    threshold: 10,
    windowClosedAt: null,
    windowOpenedAt: "2026-09-23T09:12:00Z",
  },
  flagThreshold: 0.975,
  baselineTraces: 1000,
  learningUntil: 1000,
  arlTarget: 50000,
  answers: [FLAGGED_ANSWER],
  answersNextCursor: "50",
};

export const GROUNDEDNESS_FINDING_DETAIL: BehaviorFindingDetail = {
  finding: {
    id: "fnd-1",
    title: "Answers on support-agent became less grounded",
    detector: "groundedness",
    callSiteId: "support-agent",
    caseId: null,
    causeKey: "groundedness_rate:support-agent",
    causeKind: "groundedness_rate",
    firstSeenAt: "2026-09-23T13:40:00Z",
    lastSeenAt: "2026-09-23T13:40:00Z",
    humanVerdictAt: null,
    status: "open",
    traceCount: 58,
    triageAction: null,
    triageCitations: [],
    triageStatus: "pending",
    triageSummary: null,
    triageVerdict: null,
    triagedAt: null,
    workflowKey: "support-agent",
  },
  armedWindow: null,
  frustration: null,
  groundedness: GROUNDEDNESS_DETAIL,
  malformedOutput: null,
  metric: null,
  secretLeak: null,
  toolError: null,
};

export const GROUNDEDNESS_REPORT: RcaReport = {
  id: "rca-1",
  job_id: "job-1",
  engine: "agentic",
  report_kind: "groundedness_causes",
  status: "done",
  verdict: "causes_identified",
  summary: null,
  detailed_report: null,
  call_site_id: "support-agent",
  subject_id: "support-agent",
  subject_kind: "call_site",
  subject_label: "support-agent",
  metric: "groundedness_rate",
  current_value: 0.064,
  prior_value: 0.021,
  delta: 0.043,
  window_from: "2026-09-22T09:12:00Z",
  window_split: "2026-09-23T09:12:00Z",
  window_to: "2026-09-23T15:00:00Z",
  created_at: "2026-09-23T14:20:00Z",
  completed_at: "2026-09-23T14:30:00Z",
  repo_available: true,
  ruled_out: [],
  causes: [
    {
      title: "The retriever still serves the old pricing and policy pages",
      confidence: "high",
      change: null,
      type: null,
      what_changed: "Answered refund questions from pages that were replaced.",
      how_it_caused_this: "The replaced pages quote old prices, so the answers go beyond the current documents.",
      next_step: "Remove the old folders from the index sources and rebuild the index.",
      attribution: { kind: "config", path: "support-agent/retrieval/index.yaml", commit: "a41c0de", excerpt: null },
      evidence_session_ids: [],
      evidence_trace_ids: ["t-1", "t-2", "t-3"],
      affected_count: 3,
    },
    {
      title: "The system prompt asks for a complete answer every time",
      confidence: "medium",
      change: null,
      type: null,
      what_changed: "Filled gaps with specific numbers.",
      how_it_caused_this: "Numbers the documents don't hold are flagged as unsupported.",
      next_step: "Tell the agent to say when the documents don't answer the question.",
      attribution: null,
      evidence_session_ids: [],
      evidence_trace_ids: ["t-4", "t-5"],
      affected_count: 2,
    },
  ],
};

/**
 * The same report as the current analysis writes it: every cause carries `change` and `type`, a medium
 * cause is a cause, and `ruled_out` holds one plain sentence per candidate. `GROUNDEDNESS_REPORT` keeps the
 * older shape, which stored reports still have.
 */
export const CURRENT_GROUNDEDNESS_REPORT: RcaReport = {
  ...GROUNDEDNESS_REPORT,
  summary: "Answers went unsupported because the index serves replaced pages and the prompt demands complete answers.",
  causes: [
    {
      ...GROUNDEDNESS_REPORT.causes[0],
      change: "change",
      type: "data",
      attribution: { kind: null, path: "support-agent/retrieval/index.yaml", commit: "a41c0de", excerpt: null },
    },
    {
      ...GROUNDEDNESS_REPORT.causes[1],
      change: "standing",
      type: "prompt",
      what_changed: "Fills gaps with specific numbers.",
      attribution: { kind: null, path: "support-agent/system.md", commit: null, excerpt: "Always give a complete answer." },
    },
  ],
  ruled_out: [
    {
      check: "ruled_out_1",
      question: "The serving model did not change during the window.",
      assessment: "ruled_out",
      detail: null,
      measurement: null,
      passed: true,
    },
    {
      check: "ruled_out_2",
      question: "Question traffic stayed on the same topics before and after the onset.",
      assessment: "ruled_out",
      detail: null,
      measurement: null,
      passed: true,
    },
  ],
};

export const GROUNDEDNESS_CASE_DETAIL: CaseDetail = {
  case: {
    id: "case-1",
    reference: "CASE-142",
    title: "Answers on support-agent became less grounded",
    detector: "groundedness",
    call_site_id: "support-agent",
    basis: "The flagged-answer rate rose above this call site's learned rate.",
    cause: null,
    baseline_value: 0.021,
    current_value: 0.064,
    delta: 0.043,
    disposition: null,
    finding_count: 1,
    last_seen_at: "2026-09-23T15:00:00Z",
    latest_finding_id: "fnd-1",
    locked_at: null,
    metric: "groundedness_rate",
    muted_at: null,
    muted_by: null,
    onset_at: "2026-09-23T09:12:00Z",
    opened_at: "2026-09-23T13:52:00Z",
    rca_verdict: "causes_identified",
    resolution: null,
    resolution_reason: null,
    resolved_at: null,
    resolved_by: null,
    severity: 1,
    state: "open",
    subject_id: "support-agent",
    subject_kind: "call_site",
    subject_label: "support-agent",
  },
  absorb_available: true,
  detector_available: true,
  events: [],
  exemplars: [],
  frustration: null,
  groundedness: GROUNDEDNESS_DETAIL,
  latest_finding_id: "fnd-1",
  malformed_output: null,
  metric: null,
  rca: GROUNDEDNESS_REPORT,
  rca_available: true,
  rca_report_id: "rca-1",
  ruling: null,
  secret_leak: null,
  tool_error: null,
};
