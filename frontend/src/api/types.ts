// SPDX-License-Identifier: Apache-2.0
// Wire types: aliases of the generated OpenAPI schema except string-literal unions, client
// wrappers, SSE payloads and generator-collision workarounds.
import type { components } from "./generated/schema";

type S = components["schemas"];

export type PipelineEnvelope = S["PipelineEnvelope"];

/** Generic response envelope the client unwraps; per-type generated shapes exist but the client is generic. */
export interface ApiResponse<T> {
  meta: ResponseMeta;
  data: T | null;
}

type ResponseMeta = S["ResponseMeta"];
export type ErrorBody = S["ErrorBody"];

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /** The server's message without the code prefix `message` carries, for UIs that render the code apart. */
  readonly detail: string;
  constructor(status: number, body: ErrorBody) {
    super(`${body.code}: ${body.message}`);
    this.status = status;
    this.code = body.code;
    this.detail = body.message ?? "";
  }
}

// ---- Ingestion ----
export type SubstrateStatus = S["SubstrateStatusView"];
/** `stage` is the furthest rung reached, so it never walks backwards. */
export type OnboardingProgress = S["OnboardingView"];
export type OnboardingStage = OnboardingProgress["stage"];
export type Classifier = S["ClassifierView"];
export type ClassifierEvent = S["ClassifierEventView"];
export type ClassifierHealth = S["ClassifierHealthView"];
/** `state` is computed on the server; a disabled row is `off` whatever the model does. */
export type GroundednessStatus = Omit<S["GroundednessStatusView"], "state" | "mode"> & {
  state: "off" | "on" | "not_scoring" | "not_set_up";
  mode: GroundednessMode;
};
/** `TESSARY_GROUNDEDNESS_CLASSIFIER_MODE`: where the model runs, which picks the setup and restart prompts. */
export type GroundednessMode = "dev" | "production";
export type ClassifierDailyVolume = S["ClassifierDailyVolumeView"];

// ---- Classifier charts (GET classifiers/chart-scopes, GET classifiers/charts) ----
/** The two ranges the chart endpoints accept; anything else 422s. */
export type ChartRange = 7 | 28;
/** What one charts read is scoped to: a call site, or a tool across every call site. */
export type ChartScope = { callSiteId: string } | { tool: string };
/** The selectors and the Configure menu: the call sites and tools to chart, and every classifier with its status. */
export type ChartScopes = Omit<S["ChartScopesView"], "classifiers"> & { classifiers: ClassifierMenuItem[] };
export type ChartCallSiteOption = S["CallSiteOption"];
export type ChartToolOption = S["ToolOption"];
export type ClassifierMenuItem = Omit<S["ClassifierMenuItem"], "status" | "covers"> & {
  status: "on" | "off" | "waiting";
  covers: "call_sites" | "tools" | "call_sites_and_tools";
};
/** The cards and chips for one call site or one tool over `days`. */
export type ClassifierCharts = Omit<S["ChartsView"], "scope" | "cards" | "chips"> & {
  scope: "call_site" | "tool";
  cards: ChartCard[];
  chips: ChartChip[];
};
/**
 * Rates are fractions 0..1, durations ms, cost USD; `points[]` oldest first. A count card's `count`
 * can exceed `total` for a user classifier, and its headline sums `total`.
 */
export type ChartCard = Omit<S["ChartCard"], "kind" | "unit" | "arming"> & {
  kind: "rate" | "range" | "count";
  unit: "fraction" | "ms" | "usd" | "count";
  arming: ChartArming | null;
};
type ChartArming = Omit<S["ArmingView"], "confidence"> & { confidence: "high" | "any" };
/**
 * A rate or range point merges hours until about 500 items or a day; a count point is a fixed bucket,
 * zero included. `open` marks the last point while it fills.
 */
export type ChartPoint = S["ChartPoint"];
/** One finding that opened a case, drawn as a bar in the card's Cases strip. `end_at` is null while the case is open. */
export type ChartCaseSpan = S["CaseSpan"];
/** A classifier with no card for this scope, and why. */
export type ChartChip = Omit<S["ChartChip"], "state"> & { state: "off" | "waiting" | "quiet" };
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
  | "GEMINI"
  | "GLM"
  | "GROK"
  | "CUSTOM"
  // AWS's second Bedrock endpoint: OpenAI-wire, and the only place the GPT-5.6 line lives.
  | "BEDROCK_MANTLE"
  | "TYPESAFE"
  // Supplied by the deployment, with no key of the org's own; listed only when offered.
  | "PLATFORM";

export type PlatformDescriptor = S["PlatformDescriptor"];
export type CatalogEntry = S["CatalogEntry"];
export type ProviderCatalogResponse = S["CatalogView"];

// Compile-time check that ModelProvider and the generated unions name the same members. tsc only
// evaluates a conditional type where it is consumed, so the `const` assignments below force it.
export type AssertProviderUnionsMatch<A extends string, B extends string> = [A] extends [B]
  ? [B] extends [A]
    ? true
    : never
  : never;
// Exported because `noUnusedLocals` exempts only exports.
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
export type ModelLane = S["LaneView"]["id"];
export type ModelLaneView = S["LaneView"];
export type LaneProviderOption = S["ProviderOptionView"];
export type ModelLaneGroupView = S["GroupView"];
export type BedrockModelDescriptor = S["ModelDescriptor"];
export type ProjectModelSetting = S["ProjectModelSetting"];
export type ModelSettingsResponse = S["ModelSettingsView"];
export type SetLaneModelRequest = S["SetLaneModelRequest"];
// Never hardcode a model's rate in the client. Either field null means unpriced, not free.
export type ModelRateView = S["ModelRateView"];

// ---- RCA: root-cause analysis of one finding ----
export type RcaReport = S["RcaReportView"];
export type RcaRuledOutCheck = S["RuledOutCheck"];
/** One cause an RCA found, the same shape for every case type: what changed, how it caused this, what next. */
export type RcaCause = S["Cause"];

// ---- PII redaction ----
export type RedactionRuleView = S["RuleView"];
/** Settings → Data retention: one row per data class the hourly sweep enforces. */
export type RetentionClassView = S["RetentionClassView"];
export type RetentionView = S["RetentionView"];
/** Replaces both overrides: a number sets one (0 keeps forever), `null` clears it; refused while retention is fixed. */
export type RetentionUpdateRequest = S["RetentionUpdateRequest"];

export type RedactionRuleListView = S["RuleListView"];

/** Settings → Features: one capability, what it resolves to, this build's default, and whether the org pinned it. */
export type CapabilityOverrideView = S["OverrideView"];

/** Hand-written: the generated `UpsertRuleRequest` is the alert-rule upsert, a name collision. */
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
// springdoc types these discriminators as bare `string`, so each is narrowed here.

type BehaviorCauseKind =
  | "distribution_shift"
  | "rate_shift"
  | "armed_window"
  | "malformed_rate"
  | "groundedness_rate";

type BehaviorFindingStatus = "open" | "closed";

/** The two acted-on outcomes. The third outcome is doing nothing, which posts nothing. */
export type BehaviorResolutionAction = "expected" | "not_expected";

export type BehaviorFindings = Omit<S["BehaviorFindingsView"], "findings"> & {
  findings: BehaviorFinding[];
};

/**
 * What triage concluded about the claim, never its impact. `positive` opens a case, `negative` closes
 * the finding. A run that could not settle it records no verdict; recurrence recovers a wrong close.
 */
type TriageVerdict = "positive" | "negative";

/** Sent by the server, which owns the verdict-to-action mapping. */
type TriageAction = "opened_case" | "closed";

/**
 * `pending` is un-scheduled, not un-ruled. `failed` comes from the triage job: a run that gave up
 * leaves the finding identical to one still running.
 */
type TriageStatus = "pending" | "in_flight" | "done" | "failed";

/** A second press lands on the existing job. */
export type BehaviorAnalysis = S["BehaviorAnalysisView"];

/**
 * `metric`/`toolError` are null for a behaviour-drift cause or an unreadable blob; the page renders
 * either way.
 */
export type BehaviorFindingDetail = Omit<S["BehaviorFindingDetailView"], "finding"> & {
  finding: BehaviorFinding;
};

export type EvidenceSpan = S["EvidenceSpanView"];
export type EvidenceSpanPage = S["FindingEvidenceSpanPage"];

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
 * The block a finding's owning adapter reports: the flagged rate against its reference, how a trial counts as
 * flagged, the detector's tuning, and the display text the page shows around them.
 */
export type ClassifierDetail = S["ClassifierDetail"];

/**
 * `triageVerdict`/`triageAction` stay null until a ruling, and a run that established nothing leaves
 * them null, which is why `triageStatus` exists.
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

/** `stdout` is set only for a check-script citation: what running it printed. */
export type TriageCitation = S["Citation"];

// ---- Vitals (cost / turn latency, per call site) ----
/** Read-only aggregates: nothing is detected or escalated, which keeps them out of Layer 2. */
export type Vitals = S["Vitals"];
export type VitalsGroup = S["Group"];

// ---- Cases ----
/**
 * Cause-neutral: `detector` alone says what noticed. `basis` is in the detector's own terms and is
 * never normalized. `severity` orders the list and is never rendered as a number.
 */
export type Case = S["CaseView"];
export type CaseDetail = S["CaseDetailView"];
export type TriageView = S["TriageView"];

// ---- Notifications (alert_rule / alert_channel): how a case reaches a human ----
export type AlertRule = S["AlertRuleView"];
export type UpsertAlertRule = S["UpsertAlertRuleRequest"];
export type AlertChannel = S["ChannelView"];
export type CreateAlertChannel = S["UpsertChannelRequest"];

// ---- Prompts (prompts, versions, deployment labels, usage, playground) ----
export type PromptType = "text" | "chat";
/** Stored as `{role, content}`; the server also accepts `type: "message"` or `type: "chatmessage"` on input. */
export type PromptChatMessage = { role: string; content: string };
/** Takes a list of messages when the prompt is compiled; it holds no variables. */
export type PromptPlaceholder = { type: "placeholder"; name: string };
export type PromptChatBody = (PromptChatMessage | PromptPlaceholder)[];
/** A JSON string for a text prompt, an array for a chat prompt; the schema types it as free-form JSON. */
export type PromptBody = string | PromptChatBody;
/** Free-form keys, stored as given. */
export type PromptConfig = Record<string, unknown>;

export type LabelRef = S["LabelRef"];
export type PromptSummary = S["PromptSummary"];
export type PromptPage = S["PromptPage"];
/** `labels` includes `latest` on the highest version. */
export type VersionSummary = S["VersionSummary"];
/** `versions` is newest first. */
export type PromptDetail = S["PromptDetail"];
export type PromptVersionView = Omit<S["PromptVersionView"], "body" | "config"> & { body: PromptBody; config: PromptConfig };
export type CreatePromptRequest = Omit<S["CreatePromptRequest"], "body" | "config"> & {
  body: PromptBody;
  config?: PromptConfig | null;
};
export type CreateVersionRequest = Omit<S["CreateVersionRequest"], "body" | "config"> & {
  body: PromptBody;
  config?: PromptConfig | null;
};
export type DeleteResponse = S["DeleteResponse"];
export type FolderDeleteResponse = S["FolderDeleteResponse"];
export type PromptUsageSummary = S["PromptUsageSummary"];
/** `version` is the string a span sent, null when it sent none. */
export type VersionUsage = S["VersionUsage"];
/** `unknown_versions`: version strings spans sent that this prompt does not have. */
export type PromptUsage = S["PromptUsage"];
export type PlaygroundRunRequest = S["PlaygroundRunRequest"];
export type PlaygroundRunView = S["PlaygroundRunView"];
