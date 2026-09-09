// SPDX-License-Identifier: Apache-2.0
import {
  ApiError,
  type ApiResponse,
  type CreateSourceRequest,
  type CurationEntry,
  type CurationUpdate,
  type DeleteResponse,
  type ConnectGitRequest,
  type GitIntegration,
  type IngestionSource,
  type RcaReport,
  type RedactionRuleListView,
  type RetentionUpdateRequest,
  type RetentionView,
  type RedactionRuleView,
  type UpsertRedactionRuleRequest,
  type RedactionPreviewRequest,
  type RedactionPreviewView,
  type InstallUrl,
  type ManifestStart,
  type InstallationOptions,
  type ProviderCatalogResponse,
  type ProviderCredentialListResponse,
  type ProviderCredentialView,
  type UpsertProviderCredentialRequest,
  type ModelLane,
  type ModelSettingsResponse,
  type SetLaneModelRequest,
  type ModelProvider,
  type PipelineEnvelope,
  type ProjectVersion,
  type SearchResults,
  type SubstrateStatus,
  type OnboardingProgress,
  type Classifier,
  type ClassifierDailyVolume,
  type ClassifierDebug,
  type ClassifierEvent,
  type ClassifierHealth,
  type ClassifierTuning,
  type SetClassifierTuningRequest,
  type BehaviorBaselineEvent,
  type BehaviorFinding,
  type BehaviorFindingDetail,
  type EvidenceSpanPage,
  type BehaviorAnalysis,
  type BehaviorFindings,
  type BehaviorFindingStatus,
  type Vitals,
  type BehaviorResolutionAction,
  type SessionDetailView,
  type SessionsPageView,
  type SessionSpansView,
  type TraceDetailView,
  type TracesPageView,
  type Case,
  type CaseDetail,
  type TriageView,
  type AlertRule,
  type UpsertAlertRule,
  type AlertChannel,
  type CreateAlertChannel,
  type AlertEvent,
} from "./types";
import type {
  AddMemberResult,
  ApiKey,
  ApiKeyAudit,
  AuthMode,
  BillingSummary,
  CredentialAuthResult,
  LlmUsage,
  LlmUsageSeries,
  UsageGrain,
  UsageGrouping,
  CreateKeyRequest,
  CapabilitiesView,
  IssuedKeyResponse,
  IssueTokenResponse,
  McpTokenView,
  RevokeKeyResponse,
  Me,
  OrgInvitation,
  OrgMember,
  OrgRole,
  Organization,
  Project,
  SignupPolicy,
} from "./types-auth";

/**
 * The single request choke point: every endpoint below funnels through here (only the two
 * `doImport` upload flows bypass it). Exported, not just used internally, so other request layers
 * built on this client get the same CSRF header and error handling rather than re-implementing
 * `fetch()` plumbing.
 */
export async function http<T>(path: string, init?: RequestInit): Promise<T> {
  // X-Requested-With is the backend's CSRF guard for cookie-authed /api/**
  // mutations. Browsers will not send custom headers cross-origin without a
  // CORS preflight, and we don't allow any origin to preflight, so a CSRF
  // attempt from another site can't include this header and is rejected.
  const res = await fetch(path, {
    credentials: "include",
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-Requested-With": "XMLHttpRequest",
      ...(init?.headers as Record<string, string> | undefined),
    },
  });
  // 401 from the auth filter is bare JSON, not the ApiResponse envelope.
  if (res.status === 401) {
    throw new ApiError(401, {
      code: "auth.unauthorized",
      message: "not signed in",
    });
  }
  let envelope: ApiResponse<T> | null = null;
  try {
    envelope = (await res.json()) as ApiResponse<T>;
  } catch {
    throw new ApiError(res.status, {
      code: "COMMON.INVALID_BODY",
      message: `${res.status} ${res.statusText}: response was not JSON`,
    });
  }
  if (!res.ok || !envelope.meta.success) {
    const err = envelope.meta.error ?? {
      code: "COMMON.INTERNAL",
      message: `${res.status} ${res.statusText}`,
    };
    throw new ApiError(res.status, err);
  }
  return envelope.data as T;
}

const enc = encodeURIComponent;

export interface EntityDiff {
  added: number;
  updated: number;
  removed: number;
}

export type ImportMode = "upsert" | "replace";

export interface ImportResult {
  mode: ImportMode;
  metaReplaced: boolean;
  callSites: EntityDiff;
  chains: EntityDiff;
  failureModes: EntityDiff;
  graders: EntityDiff;
  qualityDimensions: EntityDiff;
  orphanedAfterImport: number;
  repairs: string[];
}

/**
 * Both import flows share the same response envelope. They differ only in
 * Content-Type + body (YAML string vs FormData), so the response handling lives
 * in one place. {@link http} can't be reused because the request body type and
 * Content-Type aren't always JSON.
 */
async function doImport(url: string, init: RequestInit): Promise<ImportResult> {
  // Same CSRF marker as http(): see the note in http() above. We don't override
  // Content-Type here because the two import flows already set their own
  // (application/x-yaml or multipart/form-data via FormData).
  const res = await fetch(url, {
    ...init,
    headers: {
      "X-Requested-With": "XMLHttpRequest",
      ...(init.headers as Record<string, string> | undefined),
    },
  });
  const text = await res.text();
  if (!res.ok) {
    try {
      const env = JSON.parse(text);
      throw new ApiError(res.status, env.meta?.error ?? { code: "import.failed", message: text });
    } catch (e) {
      if (e instanceof ApiError) throw e;
      throw new ApiError(res.status, { code: "import.failed", message: text });
    }
  }
  const env = JSON.parse(text) as ApiResponse<ImportResult>;
  if (env.data == null) {
    throw new ApiError(res.status, { code: "import.empty_response", message: "backend returned no body" });
  }
  return env.data;
}

/** Auth + tenant endpoints: no project context needed. */
export const auth = {
  // /auth/me is served by AuthController (exempt from the auth filter); it
  // returns 401 with a JSON body when the cookie is missing/invalid. It also carries the
  // signed-in user's org list (Me.orgs), the source of "which orgs am I in" in this build.
  me: () => http<Me>("/auth/me"),

  createOrg: (name: string) =>
    http<Organization>("/api/orgs", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),

  getOrg: (orgSlug: string) => http<Organization>(`/api/orgs/${enc(orgSlug)}`),

  listProjects: (orgSlug: string) =>
    http<Project[]>(`/api/orgs/${enc(orgSlug)}/projects`),

  listMembers: (orgSlug: string) =>
    http<OrgMember[]>(`/api/orgs/${enc(orgSlug)}/members`),

  addMember: (orgSlug: string, body: { email: string; role: OrgRole }) =>
    http<AddMemberResult>(`/api/orgs/${enc(orgSlug)}/members`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  listInvitations: (orgSlug: string) =>
    http<OrgInvitation[]>(`/api/orgs/${enc(orgSlug)}/invitations`),

  getSignupPolicy: (orgSlug: string) =>
    http<SignupPolicy>(`/api/orgs/${enc(orgSlug)}/signup-policy`),

  updateSignupPolicy: (orgSlug: string, body: Pick<SignupPolicy, "mode" | "domains">) =>
    http<SignupPolicy>(`/api/orgs/${enc(orgSlug)}/signup-policy`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  revokeInvitation: (orgSlug: string, invitationId: string) =>
    http<null>(`/api/orgs/${enc(orgSlug)}/invitations/${enc(invitationId)}`, {
      method: "DELETE",
    }),

  updateMember: (orgSlug: string, userId: string, body: { role: OrgRole }) =>
    http<OrgMember>(`/api/orgs/${enc(orgSlug)}/members/${enc(userId)}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),

  removeMember: (orgSlug: string, userId: string) =>
    http<null>(`/api/orgs/${enc(orgSlug)}/members/${enc(userId)}`, {
      method: "DELETE",
    }),



  createProject: (orgSlug: string, body: { name: string; description?: string | null }) =>
    http<Project>(`/api/orgs/${enc(orgSlug)}/projects`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  /**
   * The connect gate's quiet escape hatch: idempotent, a second call (a double click, a
   * stale tab) returns the same sample project rather than minting a duplicate.
   */
  ensureSampleProject: (orgSlug: string) =>
    http<Project>(`/api/orgs/${enc(orgSlug)}/sample-project`, { method: "POST" }),

  // ---- Billing: the org usage rollup (Settings → Usage, BILLING_MANAGE-gated) ----
  // There is no charging surface behind these: what's left under /billing is metered consumption.
  // getBilling has no caller in this bundle; it stays exported for a build that adds one.

  /** Cross-project usage rollup + plan (Settings → Usage, BILLING_MANAGE-gated). */
  getBilling: (orgSlug: string) =>
    http<BillingSummary>(`/api/orgs/${enc(orgSlug)}/billing`),

  /**
   * The org's LLM token + cost breakdown (Settings → Usage, BILLING_MANAGE-gated). `from`/`to` are
   * ISO-8601; omitting a bound leaves that side open, i.e. the org's whole history.
   */
  getLlmUsage: (orgSlug: string, range?: { from?: string; to?: string }) => {
    const q = new URLSearchParams();
    if (range?.from) q.set("from", range.from);
    if (range?.to) q.set("to", range.to);
    const qs = q.toString();
    return http<LlmUsage>(`/api/orgs/${enc(orgSlug)}/usage/llm${qs ? `?${qs}` : ""}`);
  },

  /**
   * The same LLM usage bucketed over time: the usage chart's feed (Settings → Usage,
   * BILLING_MANAGE-gated). `grain` is the bucket width and `group` the series axis; `lane`/`project`/
   * `model` narrow the window to one key each, echoing back a key from the breakdown slices.
   *
   * Unlike the breakdown read, an omitted bound is not an open one: the server defaults `to` to now
   * and `from` to 30 days before it, because a per-bucket read of an unbounded ledger is a full scan.
   */
  getLlmUsageSeries: (
    orgSlug: string,
    params?: {
      from?: string;
      to?: string;
      grain?: UsageGrain;
      group?: UsageGrouping;
      lane?: string;
      project?: string;
      model?: string;
    },
  ) => {
    const q = new URLSearchParams();
    for (const [key, value] of Object.entries(params ?? {})) {
      if (value) q.set(key, value);
    }
    const qs = q.toString();
    return http<LlmUsageSeries>(`/api/orgs/${enc(orgSlug)}/usage/llm/series${qs ? `?${qs}` : ""}`);
  },

  /**
   * The org's capability object: one boolean per capability, resolved server-side from the
   * platform defaults, the org's plan tier, and any flag overrides. Readable by any member.
   * Server-side `CapabilityService.require` on each gated endpoint remains the authority; this is
   * what the UI is built from.
   */
  getCapabilities: (orgSlug: string) =>
    http<CapabilitiesView>(`/api/orgs/${enc(orgSlug)}/capabilities`),


  // ---- Organization lifecycle ----

  updateOrg: (orgSlug: string, body: { name: string; settings?: string | null }) =>
    http<Organization>(`/api/orgs/${enc(orgSlug)}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),

  // ---- Project lifecycle ----

  updateProject: (
    orgSlug: string,
    projectSlug: string,
    body: { name: string; description?: string | null; settings?: string | null },
  ) =>
    http<Project>(`/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}`, {
      method: "PATCH",
      body: JSON.stringify(body),
    }),

  makeProjectDefault: (orgSlug: string, projectSlug: string) =>
    http<Project>(`/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}/default`, {
      method: "POST",
    }),

  archiveProject: (orgSlug: string, projectSlug: string) =>
    http<Project>(`/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}/archive`, {
      method: "POST",
    }),

  unarchiveProject: (orgSlug: string, projectSlug: string) =>
    http<Project>(`/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}/unarchive`, {
      method: "POST",
    }),

  deleteProject: (orgSlug: string, projectSlug: string) =>
    http<null>(`/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}`, {
      method: "DELETE",
    }),

  loginUrl: (returnTo?: string) =>
    `/auth/login${returnTo ? `?returnTo=${enc(returnTo)}` : ""}`,

  /**
   * /auth/logout is POST-only (a GET endpoint could be triggered by <img src>
   * or other cross-site navigations). Caller should redirect to the returned
   * frontendUrl after the cookie is cleared.
   */
  logout: () =>
    http<{ frontendUrl: string }>("/auth/logout", { method: "POST" }),

  /**
   * Which flow the active provider drives: polled by the Login/Signup views on mount so
   * they render the right UI without a rebuild when a deployment switches providers.
   */
  mode: () => http<AuthMode>("/auth/mode"),

  /** Create a local account and sign in, via the dependency-free password provider. */
  signup: (email: string, password: string) =>
    http<CredentialAuthResult>("/auth/signup", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),

  /** Sign in with email/password, via the dependency-free password provider. */
  login: (email: string, password: string) =>
    http<CredentialAuthResult>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),
};

export interface DeviceLinkView {
  userCode: string;
  clientLabel: string | null;
  status: string;
  expiresAt: string;
}

/** Device-link browser endpoints (the /link confirm screen). Cookie-authed. */
export const link = {
  get: (userCode: string) => http<DeviceLinkView>(`/api/link/${enc(userCode)}`),

  confirm: (userCode: string, body: { org_slug: string; project_slug: string }) =>
    http<{ status: string }>(`/api/link/${enc(userCode)}/confirm`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  deny: (userCode: string) =>
    http<{ status: string }>(`/api/link/${enc(userCode)}/deny`, { method: "POST" }),
};

/**
 * Org-scoped API factory. Provider credentials are keyed by {@code (org, provider)}, one key per
 * org shared by every project in it, so the calls that read and write them live here rather than
 * in {@link projectApi}, even though the Settings → Providers page itself stays nested under a
 * project route (see {@code TenantContext.useOrgApi}). Small on purpose: this is not every
 * org-scoped endpoint, just the provider-credential ones.
 */
export function orgApi(orgSlug: string) {
  const base = `/api/orgs/${enc(orgSlug)}`;

  return {
    base,

    // ---- Provider credentials (Settings → Providers), org-wide ----
    listProviderCatalog: () => http<ProviderCatalogResponse>(`${base}/providers/catalog`),

    listProviderCredentials: () => http<ProviderCredentialListResponse>(`${base}/providers`),

    upsertProviderCredential: (provider: ModelProvider, body: UpsertProviderCredentialRequest) =>
      http<ProviderCredentialView>(`${base}/providers/${enc(provider)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),

    deleteProviderCredential: (provider: ModelProvider) =>
      http<{ deleted: boolean }>(`${base}/providers/${enc(provider)}`, {
        method: "DELETE",
      }),
  };
}

export type OrgApi = ReturnType<typeof orgApi>;

/** Project-scoped API factory. Pass {orgSlug, projectSlug} once; everything is bound. */
export function projectApi(orgSlug: string, projectSlug: string) {
  const base = `/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}`;

  return {
    base,


    // Cases: Triage's list and one case's page. Lifecycle is open → resolved, plus
    // muted; there is no claim endpoint because nothing in this product is assigned.
    getTriage: () => http<TriageView>(`${base}/cases`),
    getCase: (id: string) => http<CaseDetail>(`${base}/cases/${encodeURIComponent(id)}`),
    resolveCase: (id: string, reason: string) =>
      http<Case>(`${base}/cases/${encodeURIComponent(id)}/resolve`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    /**
     * Close the case and move the detector's reference, so the level it fired on becomes the new
     * baseline. Distinct from `resolveCase`, which closes this case and leaves the bar where it
     * was: an unchanged population then opens another case tomorrow.
     */
    absorbCase: (id: string) =>
      http<Case>(`${base}/cases/${encodeURIComponent(id)}/absorb`, { method: "POST" }),
    muteCase: (id: string) =>
      http<Case>(`${base}/cases/${encodeURIComponent(id)}/mute`, { method: "POST" }),
    unmuteCase: (id: string) =>
      http<Case>(`${base}/cases/${encodeURIComponent(id)}/unmute`, { method: "POST" }),

    // Notifications: the alert rules and delivery channels behind Settings → Notifications.
    listAlertRules: () => http<AlertRule[]>(`${base}/alert-rules`),
    upsertAlertRule: (body: UpsertAlertRule) =>
      http<AlertRule>(`${base}/alert-rules`, { method: "PUT", body: JSON.stringify(body) }),
    setAlertRuleEnabled: (id: string, enabled: boolean) =>
      http<AlertRule>(`${base}/alert-rules/${encodeURIComponent(id)}/enabled`, {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      }),
    listAlertChannels: () => http<AlertChannel[]>(`${base}/alert-channels`),
    createAlertChannel: (body: CreateAlertChannel) =>
      http<AlertChannel>(`${base}/alert-channels`, { method: "POST", body: JSON.stringify(body) }),
    deleteAlertChannel: (id: string) =>
      http<{ deleted: boolean }>(`${base}/alert-channels/${encodeURIComponent(id)}`, { method: "DELETE" }),
    listAlertEvents: (limit = 20) => http<AlertEvent[]>(`${base}/alert-events?limit=${limit}`),


    getPipeline: () => http<PipelineEnvelope>(`${base}/pipeline`),
    reloadPipeline: () => http<PipelineEnvelope>(`${base}/pipeline/reload`, { method: "POST" }),


    updateFailureMode: (id: string, body: CurationUpdate) =>
      http<CurationEntry>(`${base}/curation/failure-modes/${enc(id)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),

    updateInvariant: (name: string, body: CurationUpdate) =>
      http<CurationEntry>(`${base}/curation/invariants/${enc(name)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),



    listSources: () => http<IngestionSource[]>(`${base}/sources`),

    /**
     * Substrate liveness: whether this project's first REAL trace (via OTLP) has landed.
     * Onboarding polls this to detect the live path and distinguish it from the sample fallback.
     */
    substrateStatus: () => http<SubstrateStatus>(`${base}/substrate/status`),

    /**
     * The onboarding ladder: listening → first trace → fitting baselines → first finding → first case.
     * One read behind the whole Setup surface and the Triage warm-up panel, so the two can never
     * disagree about how far along a project is.
     */
    onboarding: () => http<OnboardingProgress>(`${base}/onboarding`),

    // ---- Classifiers (auto-classification) ----------------------------------
    // Built-in classifiers are seeded by the first successful generation run; these
    // endpoints list them, toggle a definition, and read recent detections.
    listClassifiers: () => http<Classifier[]>(`${base}/classifiers`),
    setClassifierEnabled: (id: string, enabled: boolean) =>
      http<Classifier>(`${base}/classifiers/${enc(id)}/enabled`, {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      }),
    listClassifierHealth: () => http<ClassifierHealth[]>(`${base}/classifiers/health`),
    /**
     * The detections one classifier produced, newest-first: the traces that tripped it.
     * `mode: "tracking"` narrows to the precise high-confidence band; omitting it returns the
     * full high-recall set, which is what the detail rail shows.
     */
    listClassifierEvents: (id: string, limit = 25, mode?: "discovery" | "tracking") =>
      http<ClassifierEvent[]>(
        `${base}/classifiers/${enc(id)}/events?limit=${limit}` + (mode ? `&mode=${enc(mode)}` : ""),
      ),
    /**
     * Per-classifier daily detected-trace counts + per-day project trace totals over the trailing
     * `days` UTC calendar days (oldest first, zero-filled, last bucket = today so far).
     */
    getClassifierDailyVolume: (days = 7) =>
      http<ClassifierDailyVolume>(`${base}/classifiers/metrics/daily?days=${days}`),
    /**
     * Debug bundle for one classifier: sweep-job cursor/lease detail plus family-specific fitted
     * state (metric_baseline rows for cost/duration drift, behavior_profile internals for behaviour
     * drift). Not part of the product surface; see `views/classifiers/debug`.
     */
    getClassifierDebug: (id: string) => http<ClassifierDebug>(`${base}/classifiers/${enc(id)}/debug`),
    /** The window/threshold operating point for a metric-drift classifier (cost_drift, duration_drift). */
    getClassifierTuning: (id: string) => http<ClassifierTuning>(`${base}/classifiers/${enc(id)}/tuning`),
    setClassifierTuning: (id: string, req: SetClassifierTuningRequest) =>
      http<ClassifierTuning>(`${base}/classifiers/${enc(id)}/tuning`, {
        method: "PUT",
        body: JSON.stringify(req),
      }),

    // ---- Vitals: cost / tool-error / turn-latency statistics, per call site ----
    /** Cost / tool-error / turn-latency statistics for a window, grouped by call site. */
    getVitals: (days = 7, by = "call_site", environment = "") =>
      http<Vitals>(
        `${base}/vitals?days=${days}&by=${enc(by)}` + (environment ? `&environment=${enc(environment)}` : ""),
      ),

// ---- Behaviour drift (Layer-1 trajectory classifier) -------------------

    /**
     * Scope-narrowed server-side: the row limit is per call site, not sliced across all of them.
     * Layer-2 gated by default; `include: "all"` is the raw Layer-1 stream.
     */
    listBehaviorFindings: (
      status: BehaviorFindingStatus = "open",
      include: "confirmed" | "all" = "confirmed",
      callSiteId?: string,
      /** Narrow to one classifier's findings: three write to the same table. */
      detector?: string,
    ) =>
      http<BehaviorFindings>(
        `${base}/findings?status=${enc(status)}&include=${enc(include)}` +
          (callSiteId ? `&callSiteId=${enc(callSiteId)}` : "") +
          (detector ? `&detector=${enc(detector)}` : ""),
      ),

    /** One finding with its evidence parsed: the finding page's only read. */
    getBehaviorFinding: (id: string) =>
      http<BehaviorFindingDetail>(`${base}/findings/${enc(id)}`),

    /**
     * One page of a finding's evidence, joined to the spans it names.
     *
     * <p>Paged rather than read off the finding: a tool-error cause can cite 27,000 refs, and
     * attaching them to the finding view would download the whole set just to open one finding.
     */
    getBehaviorFindingEvidence: (id: string, params?: { role?: string; limit?: number; cursor?: string }) => {
      const q = new URLSearchParams();
      if (params?.role) q.set("role", params.role);
      if (params?.limit != null) q.set("limit", String(params.limit));
      if (params?.cursor) q.set("cursor", params.cursor);
      const qs = q.toString();
      return http<EvidenceSpanPage>(`${base}/findings/${enc(id)}/evidence${qs ? `?${qs}` : ""}`);
    },

    /**
     * Hand one finding to Layer 2: a repo-grounded microVM ruling the deviation against the
     * committed spec. Nothing does this automatically, which is why it is a verb here. 409s only
     * when the finding cites no evidence at all.
     */
    analyzeBehaviorFinding: (id: string) =>
      http<BehaviorAnalysis>(`${base}/findings/${enc(id)}/analysis`, { method: "POST" }),

    /** Record a human decision on one finding. Leaving a finding alone is also a valid outcome
     *  and posts nothing: persistence graduation then runs as normal. */
    resolveBehaviorFinding: (id: string, action: BehaviorResolutionAction) =>
      http<BehaviorFinding>(`${base}/findings/${enc(id)}/resolution`, {
        method: "POST",
        body: JSON.stringify({ action }),
      }),

    /** The baseline changelog, newest first. */
    listBehaviorBaselineEvents: (limit = 100) =>
      http<BehaviorBaselineEvent[]>(`${base}/findings/baseline-events?limit=${limit}`),

    // ---- Traces read API (list / detail) -----------------------------------
    // Ingested production traces, one item per producer trace id, newest first. Every number on the
    // row is a rollup column the worker wrote; the request filters, sorts and pages, and computes
    // nothing. Filters compose with AND; sort is when|tokens|cost|latency, NULLS LAST; keyset-paginated
    // via the opaque `cursor` (pass the page's next_cursor back).
    listTraces: (params?: {
      limit?: number;
      cursor?: string;
      model?: string;
      kind?: string;
      fromTimestamp?: string;
      toTimestamp?: string;
      environment?: string;
      status?: string;
      q?: string;
      /** Scope to one call site (server-side only). */
      callSite?: string;
      sort?: string;
    }) => {
      const p = new URLSearchParams();
      Object.entries(params ?? {}).forEach(([k, v]) => {
        if (v != null && v !== "") p.set(k, String(v));
      });
      const qs = p.toString();
      return http<TracesPageView>(`${base}/traces${qs ? `?${qs}` : ""}`);
    },

    /**
     * One trace in full: its own row (rollups and all) plus its ordered spans.
     *
     * `traceId` is the producer's trace id. A legacy v1 ULID from a bookmark or a Slack link still
     * resolves: the server translates it through the id map until that map is retired.
     */
    getTrace: (traceId: string) => http<TraceDetailView>(`${base}/traces/${enc(traceId)}`),

    // ---- Sessions read API -------------------------------------------------
    // One continuous interaction with one user. Sessions carry no rollup of their own: the detail
    // sums its traces' rollup columns and reports `unsettled_traces` so a sum over traces still
    // receiving spans is readable as the lower bound it is. There is deliberately no sort
    // parameter: ordering sessions by cost or tokens would mean summing every session in the
    // project before a page could be chosen.
    listSessions: (params?: { limit?: number; cursor?: string; include?: "totals" }) => {
      const p = new URLSearchParams();
      Object.entries(params ?? {}).forEach(([k, v]) => {
        if (v != null && v !== "") p.set(k, String(v));
      });
      const qs = p.toString();
      return http<SessionsPageView>(`${base}/sessions${qs ? `?${qs}` : ""}`);
    },

    getSession: (sessionId: string) => http<SessionDetailView>(`${base}/sessions/${enc(sessionId)}`),

    /** Every span across a session's traces, in one read: see {@link SessionSpansView}. */
    getSessionSpans: (sessionId: string) =>
      http<SessionSpansView>(`${base}/sessions/${enc(sessionId)}/spans`),

    createSource: (body: CreateSourceRequest) =>
      http<IngestionSource>(`${base}/sources`, { method: "POST", body: JSON.stringify(body) }),

    deleteSource: (id: string) =>
      http<DeleteResponse>(`${base}/sources/${enc(id)}`, { method: "DELETE" }),

    // Project version timeline (one entry per commit SHA the platform attached to).
    listVersions: () => http<ProjectVersion[]>(`${base}/versions`),




    // ---- Git integration + observer ---------------------------------------
    // Coalesce to null: when the project has no integration the backend sends
    // an empty body, so the envelope's `data` is undefined, but TanStack Query
    // rejects undefined query results, so normalize it to null here.
    getGitIntegration: () =>
      http<GitIntegration | null>(`${base}/git`).then((v) => v ?? null),
    /** Hosted GitHub App install URL: open it to install the app, then the
     *  backend callback redirects back to the SPA with ?connected=1. */
    getGithubInstallUrl: () => http<InstallUrl>(`${base}/git/github/install-url`),
    /** Standalone OAuth authorize URL: reuses an installation already on the user's account
     *  (the 2nd+ project case). GitHub returns to the callback, which either auto-connects the
     *  single repo or lands the SPA on the Setup picker with ?install_select=<token>. */
    getGithubAuthorizeUrl: () => http<InstallUrl>(`${base}/git/github/authorize-url`),
    /** The BYO GitHub App manifest wizard's starting point: the frontend auto-submits
     *  the returned `manifest` as a POSTed form field to `url` (github.com/settings/apps/new). */
    getGithubManifestStart: () => http<ManifestStart>(`${base}/git/github/manifest-url`),
    /** The repos behind a selection token (the picker). */
    getGithubInstallationOptions: (token: string) =>
      http<InstallationOptions>(`${base}/git/github/installation-options?token=${enc(token)}`),
    /** Finalize a picker choice: binds the chosen repo/installation to this project. */
    selectGithubInstallation: (body: { token: string; installationId: number; repoOwner: string; repoName: string }) =>
      http<GitIntegration>(`${base}/git/github/select-installation`, { method: "POST", body: JSON.stringify(body) }),
    connectGit: (body: ConnectGitRequest) =>
      http<GitIntegration>(`${base}/git/connect`, { method: "POST", body: JSON.stringify(body) }),
    disconnectGit: () => http<{ deleted: boolean }>(`${base}/git`, { method: "DELETE" }),

    // ---- Import (populates project pipeline) -------------------------------
    //
    // Multipart-only: upload the full .tessary/ directory emitted by the
    // evals plugin (sharded pipeline/, graders/, datasets/...).
    //   mode = "upsert"  (default): never delete; insert new, update existing.
    //   mode = "replace" (opt-in):  full sync; entities not in upload are removed.
    importEvalsDirectory: (files: File[], mode: ImportMode = "upsert") => {
      const form = new FormData();
      // Preserve each file's relative path (webkitdirectory leaves it on
      // file.webkitRelativePath) so the backend can classify each shard.
      for (const f of files) {
        const relPath = (f as File & { webkitRelativePath?: string }).webkitRelativePath;
        const name = relPath && relPath.length > 0 ? relPath : f.name;
        form.append("files", f, name);
      }
      return doImport(`${base}/import?mode=${mode}`, {
        method: "POST",
        credentials: "include",
        body: form,
      });
    },









    // ---- MCP personal tokens (member-mintable) -----------------------------
    listMcpTokens: () => http<McpTokenView[]>(`${base}/mcp-tokens`),

    issueMcpToken: (name: string) =>
      http<IssueTokenResponse>(`${base}/mcp-tokens`, {
        method: "POST",
        body: JSON.stringify({ name }),
      }),

    revokeMcpToken: (tokenId: string) =>
      http<{ revoked: boolean }>(`${base}/mcp-tokens/${enc(tokenId)}`, {
        method: "DELETE",
      }),

    // ---- Managed API keys (project-scoped) ---------------------------------
    // List includes revoked keys (filter client-side on revoked_at if needed).
    listApiKeys: () => http<ApiKey[]>(`${base}/api-keys`),

    // 201: returns the one-time plaintext secret under `plaintext`.
    createApiKey: (body: CreateKeyRequest) =>
      http<IssuedKeyResponse>(`${base}/api-keys`, {
        method: "POST",
        body: JSON.stringify(body),
      }),

    // 201: re-issues the secret for an existing key, returning a fresh plaintext.
    rotateApiKey: (keyId: string) =>
      http<IssuedKeyResponse>(`${base}/api-keys/${enc(keyId)}/rotate`, {
        method: "POST",
      }),

    revokeApiKey: (keyId: string) =>
      http<RevokeKeyResponse>(`${base}/api-keys/${enc(keyId)}`, {
        method: "DELETE",
      }),

    // Newest-first, capped at 200 rows server-side.
    listApiKeyAudit: () => http<ApiKeyAudit[]>(`${base}/api-keys/audit`),

    // ---- Global search: tenant-scoped FTS across content entities ----------
    /**
     * Full-text search across the project's content, returning ranked, typed hits for the ⌘K
     * palette. Pass an {@link AbortSignal} so an in-flight request can be cancelled when the query
     * changes (debounced typeahead), so a stale earlier response can't race a newer one. A blank
     * query returns no hits.
     */
    search: (q: string, signal?: AbortSignal) =>
      http<SearchResults>(`${base}/search?q=${enc(q)}`, { signal }),

    // ---- RCA: root-cause analysis of one finding ----
    /**
     * Press RCA on a case. There is no body: the server resolves the finding behind the case, and
     * that id is the only thing that reaches the analysis lane: nothing the case says about the
     * finding, and nothing an earlier pass ruled about it, crosses with it. Re-pressing the same
     * case coalesces onto the existing report, which may already be running or done.
     */
    runCaseRca: (caseId: string) =>
      http<RcaReport>(`${base}/cases/${enc(caseId)}/rca`, { method: "POST" }),
    getRcaReport: (id: string) => http<RcaReport>(`${base}/rca/${enc(id)}`),
    /**
     * Re-run the analysis behind a finished report. Reports are immutable, so this returns a new
     * report to navigate to (a re-run while the first one is still running is a no-op that hands
     * back the running report).
     */
    rerunRca: (id: string) => http<RcaReport>(`${base}/rca/${enc(id)}/rerun`, { method: "POST" }),




    // Provider credentials moved to orgApi(); see that factory's own comment.

    // ---- Per-lane platform model settings (Settings → Models) --------------
    // The GET carries the capability matrix as well as the current settings, so the UI never has to
    // hardcode which models support which service tier.
    getModelSettings: () => http<ModelSettingsResponse>(`${base}/model-settings`),

    // Both mutations return the full refreshed view, so a caller can replace its cache outright
    // rather than patching one row and hoping the rest still matches. That matters more on the reset
    // below than it looks: the lane it clears may land on a different model than the one it left.
    setLaneModel: (lane: ModelLane, body: SetLaneModelRequest) =>
      http<ModelSettingsResponse>(`${base}/model-settings/${enc(lane)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),

    // DELETE drops this project's choice for the lane, returning it to automatic: the best model in
    // the lane's priority order that the org's configured providers can serve. Hence a full view
    // back, same as the PUT: which model that turns out to be is the server's answer, not ours.
    resetLaneModel: (lane: ModelLane) =>
      http<ModelSettingsResponse>(`${base}/model-settings/${enc(lane)}`, {
        method: "DELETE",
      }),

    // ---- Data retention (Settings → Data retention) ------------------------
    getRetention: () => http<RetentionView>(`${base}/retention`),

    updateRetention: (body: RetentionUpdateRequest) =>
      http<RetentionView>(`${base}/retention`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),

    // ---- PII redaction (Settings → PII redaction playground) --------------
    listRedactionRules: () => http<RedactionRuleListView>(`${base}/redaction/rules`),

    createRedactionRule: (body: UpsertRedactionRuleRequest) =>
      http<RedactionRuleView>(`${base}/redaction/rules`, {
        method: "POST",
        body: JSON.stringify(body),
      }),

    updateRedactionRule: (ruleId: string, body: UpsertRedactionRuleRequest) =>
      http<RedactionRuleView>(`${base}/redaction/rules/${enc(ruleId)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),

    setRedactionRuleEnabled: (ruleId: string, enabled: boolean) =>
      http<RedactionRuleView>(`${base}/redaction/rules/${enc(ruleId)}/enabled`, {
        method: "PUT",
        body: JSON.stringify({ enabled }),
      }),

    deleteRedactionRule: (ruleId: string) =>
      http<null>(`${base}/redaction/rules/${enc(ruleId)}`, {
        method: "DELETE",
      }),

    previewRedaction: (body: RedactionPreviewRequest) =>
      http<RedactionPreviewView>(`${base}/redaction/preview`, {
        method: "POST",
        body: JSON.stringify(body),
      }),









  };
}

export type ProjectApi = ReturnType<typeof projectApi>;
