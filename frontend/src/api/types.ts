// SPDX-License-Identifier: Apache-2.0
// Wire types. Single source of truth: the generated OpenAPI schema map
// (`./generated/schema.d.ts`, `components["schemas"]`). Most types below are thin
// re-exports/aliases of a generated schema; only pure string-literal unions (inlined
// by the generator, not named schemas), generic client wrappers, SSE event payloads,
// and a few generator-collision workarounds remain hand-authored.
import type { components } from "./generated/schema";

type S = components["schemas"];

export type PipelineEnvelope = S["PipelineEnvelope"];

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
  constructor(status: number, body: ErrorBody) {
    super(`${body.code}: ${body.message}`);
    this.status = status;
    this.code = body.code;
    this.detail = body.message ?? "";
  }
}

// ---- Ingestion + bulk grading ----
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
/**
 * The Groundedness row's status: whether the model is scoring, and if not, whether it ever was. `state`
 * is computed on the server; a disabled row is `off` whatever the model does.
 */
export type GroundednessStatus = Omit<S["GroundednessStatusView"], "state" | "mode"> & {
  state: "off" | "on" | "not_scoring" | "not_set_up";
  mode: GroundednessMode;
};
/** `TESSARY_GROUNDEDNESS_CLASSIFIER_MODE`: where the model runs, which picks the setup and restart prompts. */
export type GroundednessMode = "dev" | "production";
export type ClassifierDailyVolume = S["ClassifierDailyVolumeView"];
export type ClassifierDebug = S["ClassifierDebugView"];
export type ClassifierTuning = S["TuningView"];
export type SetClassifierTuningRequest = S["SetTuningRequest"];
export type ClassifierDebugSweep = S["SweepView"];
export type ClassifierDebugMetricBaseline = S["MetricBaselineView"];
export type ClassifierDebugSketch = S["SketchSummary"];
export type TraceListItemView = S["TraceListItem"];
export type TracesPageView = S["TracesPage"];
export type TraceDetailView = S["TraceDetail"];
export type SessionListItemView = S["SessionListItem"];
export type SessionsPageView = S["SessionsPage"];
export type SessionDetailView = S["SessionDetail"];
export type SessionSpansView = S["SessionSpans"];

export type IngestionSource = S["SourceResponse"];
export type CreateSourceRequest = S["CreateSourceRequest"];

export type GitIntegration = S["GitIntegrationView"];

export type ConnectGitRequest = S["ConnectRequest"];
export type InstallUrl = S["InstallUrlView"];
export type ManifestStart = S["ManifestStartView"];
export type InstallationOption = S["RepoOption"];
export type InstallationOptions = S["InstallationOptionsView"];

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
  | "BEDROCK_MANTLE"
  // Decision models only (TypeSafe's Jev), never a chat or agent model.
  | "TYPESAFE";

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
// One section of the Models page. The lanes split by how the platform reaches the model: a model id
// handed to an agent in a sandbox, or one typed question to a decision model. That split decides the
// heading, the copy under it and whether there is a model to pick at all, so the server sends them.
export type ModelLaneGroupView = S["GroupView"];
// One platform Bedrock model: its display name, whether it can drive an agent, and which endpoint
// serves it.
export type BedrockModelDescriptor = S["ModelDescriptor"];
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
/** One cause a frustration report found: what the agent did, and the frustrated sessions that show it. */
export type RcaCause = S["Cause"];

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
// The backend search index covers trace ids only.
export type SearchHitType = "trace";
export type SearchHit = S["SearchHit"];
export type SearchResults = S["GlobalSearchView"];

// ---- Findings ----
// The generated schema types these discriminators as bare `string` (springdoc has no enum to read
// from: they are String constants on the Java rows), so each is narrowed to its closed set here.

/**
 * The cause kinds the findings surface carries: metric drift's `distribution_shift`, tool error's
 * `rate_shift`, secret leak's `armed_window`, malformed output's `malformed_rate` and groundedness's
 * `groundedness_rate`.
 */
export type BehaviorCauseKind =
  | "distribution_shift"
  | "rate_shift"
  | "armed_window"
  | "malformed_rate"
  | "groundedness_rate";

export type BehaviorFindingStatus = "open" | "closed";

/** The two acted-on outcomes. The third outcome is doing nothing, which posts nothing. */
export type BehaviorResolutionAction = "expected" | "not_expected";

/** The findings page. */
export type BehaviorFindings = Omit<S["BehaviorFindingsView"], "findings"> & {
  findings: BehaviorFinding[];
};

/**
 * What triage concluded about a finding's claim: never about its impact.
 *
 * - `positive`: the claim is true, sufficiently sampled and properly evidenced. Opens a case.
 * - `negative`: the measurement is wrong, so there is nothing to explain. Closes the finding.
 *
 * A run that could not settle the question, or never reached the evidence, records no verdict at
 * all — recurrence is the recovery for a wrong close, not a third outcome to rule.
 *
 * A cost or duration drop rules `positive` like any other true claim. "Improvement" is a judgement about
 * intent, and triage has no evidence with which to make one.
 */
export type TriageVerdict = "positive" | "negative";

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
export type BehaviorAnalysis = S["BehaviorAnalysisView"];

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

export type EvidenceSpan = S["EvidenceSpanView"];
export type EvidenceSpanPage = S["FindingEvidenceSpanPage"];

/**
 * A `malformed_rate` finding's "How outputs broke": the rate, the declared schema annotated with a
 * failure count per field, and the two buckets no declared field owns (`notJson`, `other`).
 */
export type MalformedOutputDetail = S["MalformedDetail"];
export type MalformedOutputSchemaField = S["SchemaFieldView"];

/** One failing output for a selected schema field, and the page it came from. */
export type MalformedOutputRow = S["FailingOutputView"];
export type MalformedOutputPage = S["FailingOutputPage"];

/**
 * A `frustration_rate` finding's block: the frustrated-conversation rate against the call site's learned
 * rate, and the conversations the finding cites, each with the turn that fired in it.
 */
export type FrustrationDetail = S["FrustrationDetail"];
export type FrustratedConversation = S["FrustratedConversationView"];
export type FrustratedSessionPage = S["FrustratedSessionPage"];

/**
 * A `groundedness_rate` finding's block: the flagged-answer rate against the call site's learned rate, and
 * the flagged answers the finding cites, each with the sentences the model marked in it.
 */
export type GroundednessDetail = S["GroundednessDetail"];
export type FlaggedAnswer = S["FlaggedAnswerView"];
export type FlaggedAnswerPage = S["FlaggedAnswerPage"];

/**
 * One finding, with the triage fields narrowed to the vocabulary the server writes.
 *
 * `triageVerdict` / `triageAction` are null until a run has recorded a ruling, and a run that never
 * settled the question or never reached the evidence leaves them null rather than writing a verdict
 * for a run that established nothing: that is what makes "closing on negative" safe to automate, and
 * it is why `triageStatus` is a separate field: a null verdict alone cannot tell a finding nobody has
 * looked at from one whose infra blipped.
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
 * One thing a ruling rests on: an evidence pointer, an id the agent fetched, or a check script it
 * wrote. `stdout` is set only for a citation that IS a check script — exactly what running it
 * printed.
 */
export type TriageCitation = S["Citation"];

// ---- Vitals (cost / turn latency, per call site) ----
/**
 * The three deterministic statistical filters. Read-only aggregates over ingested substrate: no
 * detections are written and nothing escalates, which is what keeps them out of Layer 2.
 */
export type Vitals = S["Vitals"];
export type VitalsGroup = S["Group"];

// ---- Cases (eval_case): the one object a detection reaches a human through ----
/**
 * Cause-neutral by design: `detector` is the only field that says what noticed, and every
 * detector's cases carry the same shape. `basis` is why this crossed that detector's bar, in its
 * own terms: the ranked list mixes detectors that do not share a threshold, so it is never
 * normalized away. `severity` orders the list and must never be rendered as a number.
 */
export type Case = S["CaseView"];
export type CaseDetail = S["CaseDetailView"];
/**
 * What a person said a resolved frustration case turned out to be. Both restart the call site's
 * learned rate; `false_alarm` also clears the conversations the case cites. No other case takes one.
 */
export type CaseDisposition = "fixed" | "false_alarm";
export type TriageView = S["TriageView"];

// ---- Notifications (alert_rule / alert_channel): how a case reaches a human ----
export type AlertRule = S["AlertRuleView"];
export type UpsertAlertRule = S["UpsertAlertRuleRequest"];
export type AlertChannel = S["ChannelView"];
export type CreateAlertChannel = S["UpsertChannelRequest"];
