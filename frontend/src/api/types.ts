// SPDX-License-Identifier: Apache-2.0
// Wire types. Single source of truth: the generated OpenAPI schema map
// (`./generated/schema.d.ts`, `components["schemas"]`). Most types below are thin
// re-exports/aliases of a generated schema; only pure string-literal unions (inlined
// by the generator, not named schemas), generic client wrappers, SSE event payloads,
// and a few generator-collision workarounds remain hand-authored.
import type { components } from "./generated/schema";

type S = components["schemas"];

export type Pipeline = S["Pipeline"];
export type Progress = S["Progress"];
export type InvariantCoverage = S["InvariantCoverage"];
export type Pack = S["Pack"];
export type Runtime = S["Runtime"];
export type ProductProfile = S["ProductProfile"];
export type EvidencedSignal = S["EvidencedSignal"];
export type ImplicitInvariant = S["ImplicitInvariant"];

/**
 * Shape enum (evals plugin v0.3). Inlined by the generator, kept hand-authored so
 * consumers can narrow for display + filtering.
 */
export type CallSiteShape =
  | "summarize"
  | "extract"
  | "rag_answer"
  | "classify"
  | "draft"
  | "route"
  | "tool_call"
  | "agent_step"
  | "conversational_turn"
  | "embedding"
  | "rerank"
  | "guardrail"
  | "moderation"
  | "ensemble_vote"
  | "other";

/** How the model is reached (evals-synth v0.9). */
export type CallSiteInvocation = "sdk" | "cli_agent" | "http" | "sandbox_agent";

export type CallSite = S["CallSite"];
export type SourceSpan = S["SourceSpan"];
export type Observed = S["Observed"];

export type ChainDetectionMethod =
  | "trace_confirmed"
  | "ensemble"
  | "state_mediated"
  | "sequential_composition";

export type Chain = S["Chain"];
export type FailureMode = S["FailureMode"];

/**
 * Grader (grader-author contract v7). The generated schema drops a handful of
 * operational fields the backend still serializes (`owner`, `applies_when_check`,
 * `cost_budget_tokens`, `latency_budget_ms_p95`): see report (candidate backend
 * annotation fix). They are re-added here as optional so consumers keep compiling.
 */

export type TaxonomyNode = S["TaxonomyNode"];

// Curation overlay
export type CurationStatus = "pending" | "accepted" | "rejected" | "edited" | "orphaned";

/**
 * Kept hand-authored: the generated `CurationEntry`/`Curation` schemas mark every
 * field optional and use camelCase `updatedAt`/`failureModes` (inconsistent with the
 * snake_case wire), which would force spurious null-guards across many consumers.
 * See report (candidate backend annotation fix).
 */
export interface CurationEntry {
  id: string;
  status: CurationStatus;
  notes: string;
  edits: Record<string, string>;
  updated_at: string;
}

export interface Curation {
  version: string;
  graders: Record<string, CurationEntry>;
  failure_modes: Record<string, CurationEntry>;
  invariants: Record<string, CurationEntry>;
  /** Per-call-site overlays. `edits.grade_mode` toggles grade-per-conversation. */
  call_sites: Record<string, CurationEntry>;
}

/** Call-site curation edit key + values for the grading-mode toggle. */
export const GRADE_MODE = "grade_mode";
export type GradeMode = "per_conversation" | "per_turn";

export type PipelineEnvelope = S["PipelineEnvelope"];

export interface CurationUpdate {
  status?: CurationStatus;
  notes?: string;
  edits?: Record<string, string>;
}

/** Generic response envelope the client unwraps; per-type generated shapes exist but the client is generic. */
export interface ApiResponse<T> {
  meta: ResponseMeta;
  data: T | null;
}

export type ResponseMeta = S["ResponseMeta"];
export type ErrorBody = S["ErrorBody"];

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /**
   * The server's message on its own, without the code prefix that `Error.message` carries.
   *
   * `message` stays `"<code>: <message>"` because that is what a log line and a stack trace want.
   * A UI that renders the code beside the message needs the bare half, and reaching for `message`
   * there prints the code twice: which is exactly what `ErrorNote` did until this existed.
   */
  readonly detail: string;
  readonly details?: Record<string, string>;
  constructor(status: number, body: ErrorBody) {
    super(`${body.code}: ${body.message}`);
    this.status = status;
    this.code = body.code;
    this.detail = body.message ?? "";
    this.details = body.details ?? undefined;
  }
}

// ---- Ingestion + bulk grading ----
// Synthetic, non-network source providers: direct OTLP/SDK ingest (`sdk`) and the
// uploaded-JSONL sink (`upload`). Vendor-pull providers were removed with the pull adapters.
export type Provider = "sdk" | "upload";

export type SubstrateStatus = S["SubstrateStatusView"];
/**
 * Where a project sits on the ladder from "listening" to "first case": the single read the whole
 * onboarding surface is built from (launch G3/G4). `stage` is the furthest rung reached, so it never
 * walks backwards; every other field is the evidence behind whichever rung that is.
 */
export type OnboardingProgress = S["OnboardingView"];
export type OnboardingStage = OnboardingProgress["stage"];
export type Classifier = S["ClassifierView"];
export type ClassifierEvent = S["ClassifierEventView"];
export type ClassifierHealth = S["ClassifierHealthView"];
export type ClassifierDailyVolume = S["ClassifierDailyVolumeView"];
export type ClassifierDebug = S["ClassifierDebugView"];
export type ClassifierTuning = S["TuningView"];
export type SetClassifierTuningRequest = S["SetTuningRequest"];
export type ClassifierDebugSweep = S["SweepView"];
export type ClassifierDebugMetricBaseline = S["MetricBaselineView"];
export type ClassifierDebugSketch = S["SketchSummary"];
export type ClassifierDebugBehaviorProfile = S["BehaviorProfileDebugView"];
export type TraceListItemView = S["TraceListItem"];
export type TracesPageView = S["TracesPage"];
export type TraceToolCallView = S["ToolCallView"];
export type TraceRetrievalDocumentView = S["RetrievalDocumentView"];
/** One step of a trace. The v2 substrate calls it a span; `ObservationView` is gone with the v1 wire. */
export type SpanView = S["SpanView"];
export type TraceDetailView = S["TraceDetail"];
export type SessionListItemView = S["SessionListItem"];
export type SessionsPageView = S["SessionsPage"];
export type SessionDetailView = S["SessionDetail"];
export type SessionSpansView = S["SessionSpans"];

export type IngestionSource = S["SourceResponse"];
export type CreateSourceRequest = S["CreateSourceRequest"];

export type DatasetKind = "kv" | "chat" | "span_sourced";
export type DatasetCategory = "golden" | "sample";

export type ProjectVersion = S["ProjectVersionView"];
export type GitIntegration = S["GitIntegrationView"];

export type ConnectGitRequest = S["ConnectRequest"];
export type InstallUrl = S["InstallUrlView"];
export type ManifestStart = S["ManifestStartView"];
export type InstallationOption = S["RepoOption"];
export type InstallationOptions = S["InstallationOptionsView"];

export type DeleteResponse = S["DeleteResponse"];

// ---- Model configuration ----
export type ModelProvider =
  | "OPENAI"
  | "ANTHROPIC"
  | "OPENROUTER"
  | "MOONSHOT"
  | "BEDROCK"
  // Four OpenAI-compat providers, all speaking the same Chat Completions wire as
  // OpenRouter/Moonshot against their own base URL (see PlatformCatalog.java).
  | "GEMINI"
  | "GLM"
  | "GROK"
  | "CUSTOM"
  // AWS's second Bedrock endpoint: OpenAI-wire, and the only place the GPT-5.6 line lives.
  | "BEDROCK_MANTLE";

// Every provider authenticates with an org-supplied key.
export type PlatformAuth = "api_key" | "aws";
export type PlatformDescriptor = S["PlatformDescriptor"];
export type CatalogEntry = S["CatalogEntry"];
export type ProviderCatalogResponse = S["CatalogView"];

// Compile-time-only check (never referenced at runtime): ModelProvider above and the generated
// PlatformDescriptor["id"]/CatalogEntry["provider"] unions must name exactly the same members as
// the backend ModelProvider enum (this file's header already documents ModelProvider as a
// hand-maintained exception to the generated-schema pattern, precisely because nothing else
// enforces the two staying in sync) instead of silently undercounting what /catalog actually
// returns. See PlatformDescriptor's own generated `id` type.
//
// A member added to one side and not the other fails `tsc`, but only because of the two `const`
// assignments below, not because of this type alias by itself. A `type X = AssertProviderUnionsMatch<
// A, B>` that resolves to `never` and is merely exported raises no diagnostic on its own: tsc only
// evaluates a conditional type where it is actually consumed, and an exported-but-unreferenced
// alias (the shape `noUnusedLocals` requires to avoid a separate unused-symbol error) is never
// consumed by anything. The `const` below forces evaluation: assigning `true` to a type that
// resolves to `never` on a mismatch is a real, unavoidable TS2322 right on this line.
export type AssertProviderUnionsMatch<A extends string, B extends string> = [A] extends [B]
  ? [B] extends [A]
    ? true
    : never
  : never;
// Exported (never re-imported anywhere) rather than a bare unreferenced local: `noUnusedLocals` is
// on in this project's tsconfig, and only exported symbols are exempt from that diagnostic. Neither
// is ever read: the assignment itself is the entire check, and CatalogEntry gets its own const
// because it is a distinct generated union from PlatformDescriptor["id"], not merely a repeat of it.
export const modelProviderMatchesPlatformCatalog: AssertProviderUnionsMatch<
  ModelProvider,
  PlatformDescriptor["id"]
> = true;
export const modelProviderMatchesModelCatalog: AssertProviderUnionsMatch<ModelProvider, CatalogEntry["provider"]> =
  true;
export type ProviderCredentialView = S["View"];
export type ProviderCredentialListResponse = S["CredentialListView"];

export type UpsertProviderCredentialRequest = S["UpsertRequest"];

// ---- Per-lane platform model settings (Settings → Models) ----
// Which of the platform's own Bedrock models each lane runs on, and at which service tier. Distinct
// from the provider credentials above: those are keys for models the customer brings and pays for.
export type ModelLane = S["LaneView"]["id"];
export type ModelLaneView = S["LaneView"];
// One provider's standing on one lane: its label, the models this lane offers on it, and the one
// automatic selection takes. The page asks for a provider first and a model second, because a key is
// what an org has or does not have: so this is the shape the two dropdowns read.
export type LaneProviderOption = S["ProviderOptionView"];
// One section of the Models page. The lanes split by how the platform reaches the model: a request
// we compose, or a model id handed to an agent in a sandbox, and that split decides the heading, the
// copy under it and whether a tier or an effort is a real choice, so the server sends all four.
export type ModelLaneGroupView = S["GroupView"];
// The Bedrock capability matrix row: which tiers, cache TTLs and reasoning-effort levels a platform
// model actually supports, and which endpoint serves it.
export type BedrockModelDescriptor = S["ModelDescriptor"];
export type ServiceTier = BedrockModelDescriptor["supported_tiers"][number];
// Reasoning effort is per model, not a fixed vocabulary: the OpenAI line takes low/medium/high while
// the GPT-5.6 models on bedrock-mantle also take none, xhigh and max. Always read the levels off the
// selected model's `effort_levels` rather than hardcoding a union: an over-narrow list here silently
// hides half the range on exactly the models that have the most of it.
export type EffortLevel = string;
export type ProjectModelSetting = S["ProjectModelSetting"];
export type ModelSettingsResponse = S["ModelSettingsView"];
export type SetLaneModelRequest = S["SetLaneModelRequest"];
// One model's live per-MTok rate, read from the price book at request time: never hardcode a
// model's rate in the client, the book can move. Either field null means unpriced, not free.
export type ModelRateView = S["ModelRateView"];

// ---- RCA: root-cause analysis of one finding ----
export type RcaReport = S["RcaReportView"];
export type RcaRuledOutCheck = S["RuledOutCheck"];
export type RcaHypothesis = S["Hypothesis"];

// ---- PII redaction ----
export type RedactionRuleView = S["RuleView"];
/** Settings → Data retention: one row per data class the hourly sweep enforces. */
export type RetentionClassView = S["RetentionClassView"];
export type RetentionView = S["RetentionView"];
/** Replaces both overrides: a number sets one (0 keeps forever), `null` clears it. */
export type RetentionUpdateRequest = S["RetentionUpdateRequest"];

export type RedactionRuleListView = S["RuleListView"];

/**
 * Kept hand-authored: the generated `UpsertRuleRequest` is the alert-rule upsert
 * (basis/threshold/window_seconds), which collides by name with this redaction-rule
 * upsert in the OpenAPI generator. See report (candidate backend schema-name collision).
 */
export interface UpsertRedactionRuleRequest {
  name: string;
  pattern: string;
  replacement: string;
  enabled?: boolean;
  sort_order?: number;
}

export type RedactionPreviewRequest = S["PreviewRequest"];

export type RedactionPreviewView = S["PreviewView"];

// ---- Global search ----
// The ⌘K contract (DESIGN-DIRECTION §7) indexes surfaces, cases, graders, and
// trace ids only: datasets have no page and are not searchable.
export type SearchHitType = "case" | "trace";
export type SearchHit = S["SearchHit"];
export type SearchResults = S["GlobalSearchView"];

// ---- Human annotations / verdict review (annotation node) ----

// ---- Behaviour drift (Layer-1 trajectory classifier) ----
// The generated schema types these discriminators as bare `string` (springdoc has no enum to read
// from: they are String constants on the Java rows), so each is narrowed to its closed set here.

/**
 * The cause kinds the findings surface carries. Behaviour drift's three (`novelty`/`omission` are
 * high confidence, `surprisal` is low), metric drift's `distribution_shift`, tool error's
 * `rate_shift`, and `sop_conformance`: a conformance finding rendered in the same shape (its
 * causeKey is the SOP rule slug, its traceCount the tested window's activations).
 */
export type BehaviorCauseKind =
  | "novelty"
  | "surprisal"
  | "omission"
  | "distribution_shift"
  | "rate_shift"
  | "sop_conformance";

/** `resolved` is conformance-only: its single human verb closes the row rather than marking it. */
export type BehaviorFindingStatus = "open" | "graduated" | "allowlisted" | "blocked" | "resolved";

/** The two acted-on outcomes. The third outcome is doing nothing, which posts nothing. */
export type BehaviorResolutionAction = "expected" | "not_expected";

/**
 * The findings page. `withheld` is what the Layer-2 gate is holding back: un-triaged findings
 * plus those a ruling called legitimate or could not settle, so an empty list can be told apart
 * from a filtered one. `lane` is which Layer-2 lane this project's findings get ruled on.
 */
export type BehaviorFindings = Omit<S["BehaviorFindingsView"], "findings" | "lane"> & {
  findings: BehaviorFinding[];
  lane: TriageLane;
};

/**
 * Which instrument ruled on a finding, and the difference is one of authority, not of quality.
 *
 * - `evidence_only`: triage: an agent in a sandbox with the finding's claim, this platform's read
 *   surface for the evidence behind it, and a directory to write check scripts in. Its citations are
 *   evidence pointers, the ids it fetched, and the scripts it ran.
 * - `grader`: the call site's own graders run against the traces the finding cites. No agent, no
 *   sandbox: the rubrics the team already wrote, executed against the evidence. Its citations are the
 *   traces that failed. Chosen by hand, never automatically.
 *
 * A third, `repo_grounded`, is gone: no source file settles whether a claim about production traffic
 * is true, and the repository went to RCA, which asks the question it answers.
 */
export type TriageLane = "evidence_only" | "grader";

/**
 * What triage concluded about a finding's claim: never about its impact.
 *
 * - `positive`: the claim is true, sufficiently sampled and properly evidenced. Opens a case.
 * - `negative`: the measurement is wrong, so there is nothing to explain. Closes the finding.
 * - `unclear`: the evidence could not settle it. Also closes: recurrence is the recovery, not a
 *   person reading a queue.
 *
 * A cost or duration drop rules `positive` like any other true claim. "Improvement" is a judgement about
 * intent, and triage has no evidence with which to make one.
 */
export type TriageVerdict = "positive" | "negative" | "unclear";

/**
 * What the ruling did. Fixed by the verdict (`sound → opened_case`, everything else → `closed`) and
 * sent anyway, because it is what the reader is being told; deriving it here would be a second copy
 * of a mapping the server owns.
 */
export type TriageAction = "opened_case" | "closed";

/**
 * Where a finding is in the triage lane. `pending` is un-scheduled, not un-ruled.
 *
 * `failed` is read from the triage job, not the finding: a run that gave up records no verdict and
 * nothing clears the escalation timestamp, so on the finding alone it is identical to one still
 * running: which is why a dead triage used to render as "Triaging" indefinitely.
 */
export type TriageStatus = "pending" | "in_flight" | "done" | "failed";

/**
 * What pressing Run analysis on a finding produced: the queued job, the lane that will rule on it,
 * and whether the cause had already been handed to Layer 2 (a second press lands on the existing job
 * rather than buying a second one).
 */
export type BehaviorAnalysis = Omit<S["BehaviorAnalysisView"], "lane"> & { lane: TriageLane };

/**
 * One finding with its evidence parsed: what the finding's own page draws.
 *
 * `metric` is set for a distribution shift and `toolError` for a rate shift; both are null for a
 * behaviour-drift cause, which carries no measured shift, and for any row whose blob is unreadable. A
 * caller renders the finding either way: the headline and the verdict never depended on the evidence
 * parsing, and a page that vanished because one column was malformed would be the worse failure.
 */
export type BehaviorFindingDetail = Omit<S["BehaviorFindingDetailView"], "finding"> & {
  finding: BehaviorFinding;
};

/**
 * One reference from a finding into the substrate it read: never a copy of it.
 *
 * `grain` is redundant with which id is set and is sent anyway, because a span reference carries both
 * ids (span identity is the composite `(project, trace, span)`) while a trace reference carries one,
 * and a client rendering a mixed list should not have to re-derive that.
 */
export type EvidenceRef = S["EvidenceRefView"];
export type EvidenceSpan = S["EvidenceSpanView"];
export type EvidenceSpanPage = S["FindingEvidenceSpanPage"];

/**
 * One finding, with the triage fields narrowed to the vocabulary the server writes.
 *
 * `triageVerdict` / `triageAction` are null until a run has recorded a ruling, and a run that never
 * happened leaves them null rather than writing `unclear`: that is what makes "unsure means close"
 * safe to automate, and it is why `triageStatus` is a separate field: a null verdict alone cannot
 * tell a finding nobody has looked at from one whose infra blipped.
 */
export type BehaviorFinding = Omit<
  S["BehaviorFindingView"],
  "causeKind" | "status" | "callSiteId" | "triageVerdict" | "triageAction" | "triageStatus"
> & {
  callSiteId: string | null;
  causeKind: BehaviorCauseKind;
  status: BehaviorFindingStatus;
  triageVerdict: TriageVerdict | null;
  triageAction: TriageAction | null;
  triageStatus: TriageStatus;
};

/**
 * One thing a ruling rests on. Part prose, part code: `stdout` is set only for a citation that IS a
 * check script the agent wrote and ran, and `recomputed` are the detector's own numbers that script
 * re-derived: every one of them already agreed with the payload, because a disagreement aborts the
 * run instead of becoming a ruling.
 */
export type TriageCitation = S["Citation"];

/**
 * The `detail` jsonb on a baseline event, which the read model passes through as a raw JSON string.
 * Readers normalise it rather than indexing it directly: the writer's key spelling is not stable.
 */
export type BehaviorBaselineEventDetail = Record<string, unknown>;

/** §4.3: one durable, readable row per baseline mutation. The product payoff surface. */
export type BehaviorBaselineEvent = Omit<S["BehaviorBaselineEventView"], "detail"> & {
  detail: BehaviorBaselineEventDetail | string | null;
};

// ---- Human review queues (annotation_queue + annotation_queue_item) ----
/** A review queue's lifecycle status. */
export type ReviewQueueStatus = "active" | "archived";

// ---- Vitals (cost / turn latency, per call site) ----
/**
 * The three deterministic statistical filters. Read-only aggregates over ingested substrate: no
 * detections are written and nothing escalates, which is what keeps them out of Layer 2.
 */
export type Vitals = S["Vitals"];
export type VitalsGroup = S["Group"];
export type VitalsCost = S["Cost"];
export type VitalsDuration = S["Duration"];

// ---- Cases (eval_case): the one object a detection reaches a human through ----
/**
 * Cause-neutral by design: `detector` is the only field that says what noticed, and every
 * detector's cases carry the same shape. `basis` is why this crossed that detector's bar, in its
 * own terms: the ranked list mixes detectors that do not share a threshold, so it is never
 * normalized away. `severity` orders the list and must never be rendered as a number.
 */
export type Case = S["CaseView"];
export type CaseDetail = S["CaseDetailView"];
export type TriageView = S["TriageView"];
/**
 * Who ruled the detection real, and what they said. `ruled_by` is `Human` or `Triage` and
 * `by_human` is the same fact as a boolean; the lane chip is gone with the lanes, because a machine
 * ruling now reaches a case exactly one way: triage audited the claim and found it sound.
 *
 * A human ruling carries no summary and no citations. That is the strongest ruling available, so it
 * must never render as an empty version of the weaker one; `ruled_by_sentence` is what it says
 * instead.
 */
export type CaseRuling = S["CaseRulingView"];
/**
 * One trace a finding pinned as evidence, carrying the `role` it was pinned under. There is no
 * before/after wrapper any more: nothing is sampled at read time, so a case shows evidence rows and
 * the role is what tells a baseline from an exemplar.
 */
export type CaseExemplar = S["CaseExemplarView"];

// ---- Notifications (alert_rule / alert_channel): how a case reaches a human ----
export type AlertRule = S["AlertRuleView"];
export type UpsertAlertRule = S["UpsertAlertRuleRequest"];
export type AlertPolicy = S["PolicyView"];
export type AlertChannel = S["ChannelView"];
export type CreateAlertChannel = S["UpsertChannelRequest"];
export type AlertEvent = S["AlertEventView"];

/** One grader's production numbers over the index window (`GET /trend/grader-stats`). */
