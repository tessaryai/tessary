// SPDX-License-Identifier: Apache-2.0
import {
  ApiError,
  type ApiResponse,
  type CreateSourceRequest,
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
  type SearchResults,
  type SubstrateStatus,
  type OnboardingProgress,
  type Classifier,
  type ClassifierDailyVolume,
  type ChartRange,
  type ChartScope,
  type ChartScopes,
  type ClassifierCharts,
  type ClassifierDebug,
  type ClassifierEvent,
  type ClassifierHealth,
  type GroundednessStatus,
  type ClassifierTuning,
  type SetClassifierTuningRequest,
  type BehaviorFinding,
  type BehaviorFindingDetail,
  type EvidenceSpanPage,
  type MalformedOutputPage,
  type FrustratedSessionPage,
  type FlaggedAnswerPage,
  type BehaviorAnalysis,
  type BehaviorFindings,
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
  type CapabilityOverrideView,
  type CreatePromptRequest,
  type CreateVersionRequest,
  type DeleteResponse,
  type FolderDeleteResponse,
  type PlaygroundRunRequest,
  type PlaygroundRunView,
  type PromptDetail,
  type PromptPage,
  type PromptType,
  type PromptUsage,
  type PromptUsageSummary,
  type PromptVersionView,
} from "./types";
import type {
  AddMemberResult,
  ApiKey,
  ApiKeyAudit,
  AuthMode,
  CredentialAuthResult,
  CreateKeyRequest,
  CapabilitiesView,
  CapabilityWire,
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

async function http<T>(path: string, init?: RequestInit): Promise<T> {
  // X-Requested-With is the backend's CSRF guard: no origin may preflight, so a cross-site request cannot send it.
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

function send<T>(method: "POST" | "PUT" | "PATCH" | "DELETE", path: string, body?: unknown): Promise<T> {
  return http<T>(path, body === undefined ? { method } : { method, body: JSON.stringify(body) });
}

/** `path` with every param that is set appended; null, undefined and "" are left out. */
function withQuery(path: string, params: object = {}): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== "") q.set(k, String(v));
  }
  const qs = q.toString();
  return qs ? `${path}?${qs}` : path;
}

/** A page of a finding's cited rows; `cause` narrows it to one RCA cause: its report and 0-based position there. */
type CausePageParams = { limit?: number; cursor?: string | null; cause?: { rcaReport: string; index: number } };

const causePage = (params?: CausePageParams) => ({
  limit: params?.limit,
  cursor: params?.cursor,
  rcaReport: params?.cause?.rcaReport,
  cause: params?.cause?.index,
});

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
  repairs: string[];
}

/** Not {@link http}: the multipart body sets its own Content-Type. */
async function doImport(url: string, init: RequestInit): Promise<ImportResult> {
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

export const auth = {
  me: () => http<Me>("/auth/me"),

  getOrg: (orgSlug: string) => http<Organization>(`/api/orgs/${enc(orgSlug)}`),

  listProjects: (orgSlug: string) =>
    http<Project[]>(`/api/orgs/${enc(orgSlug)}/projects`),

  listMembers: (orgSlug: string) =>
    http<OrgMember[]>(`/api/orgs/${enc(orgSlug)}/members`),

  addMember: (orgSlug: string, body: { email: string; role: OrgRole }) =>
    send<AddMemberResult>("POST", `/api/orgs/${enc(orgSlug)}/members`, body),

  listInvitations: (orgSlug: string) =>
    http<OrgInvitation[]>(`/api/orgs/${enc(orgSlug)}/invitations`),

  getSignupPolicy: (orgSlug: string) =>
    http<SignupPolicy>(`/api/orgs/${enc(orgSlug)}/signup-policy`),

  updateSignupPolicy: (orgSlug: string, body: Pick<SignupPolicy, "mode" | "domains">) =>
    send<SignupPolicy>("PUT", `/api/orgs/${enc(orgSlug)}/signup-policy`, body),

  revokeInvitation: (orgSlug: string, invitationId: string) =>
    send<null>("DELETE", `/api/orgs/${enc(orgSlug)}/invitations/${enc(invitationId)}`),

  updateMember: (orgSlug: string, userId: string, body: { role: OrgRole }) =>
    send<OrgMember>("PATCH", `/api/orgs/${enc(orgSlug)}/members/${enc(userId)}`, body),

  removeMember: (orgSlug: string, userId: string) =>
    send<null>("DELETE", `/api/orgs/${enc(orgSlug)}/members/${enc(userId)}`),

  createProject: (orgSlug: string, body: { name: string; description?: string | null }) =>
    send<Project>("POST", `/api/orgs/${enc(orgSlug)}/projects`, body),

  /** Idempotent: a double click or a stale tab gets the same sample project back. */
  ensureSampleProject: (orgSlug: string) =>
    send<Project>("POST", `/api/orgs/${enc(orgSlug)}/sample-project`),

  /** What the UI is built from; `CapabilityService.require` on each gated endpoint stays the authority. */
  getCapabilities: (orgSlug: string) =>
    http<CapabilitiesView>(`/api/orgs/${enc(orgSlug)}/capabilities`),

  listCapabilityOverrides: (orgSlug: string) =>
    http<CapabilityOverrideView[]>(`/api/orgs/${enc(orgSlug)}/capabilities/overrides`),

  setCapabilityOverride: (orgSlug: string, key: CapabilityWire, enabled: boolean) =>
    send<CapabilityOverrideView>("PUT", `/api/orgs/${enc(orgSlug)}/capabilities/overrides/${enc(key)}`, { enabled }),

  updateOrg: (orgSlug: string, body: { name: string; settings?: string | null }) =>
    send<Organization>("PATCH", `/api/orgs/${enc(orgSlug)}`, body),

  makeProjectDefault: (orgSlug: string, projectSlug: string) =>
    send<Project>("POST", `/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}/default`),

  archiveProject: (orgSlug: string, projectSlug: string) =>
    send<Project>("POST", `/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}/archive`),

  unarchiveProject: (orgSlug: string, projectSlug: string) =>
    send<Project>("POST", `/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}/unarchive`),

  deleteProject: (orgSlug: string, projectSlug: string) =>
    send<null>("DELETE", `/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}`),

  loginUrl: (returnTo?: string) =>
    `/auth/login${returnTo ? `?returnTo=${enc(returnTo)}` : ""}`,

  /** POST-only, so an <img src> or other cross-site navigation cannot sign anyone out. */
  logout: () =>
    send<{ frontendUrl: string }>("POST", "/auth/logout"),

  mode: () => http<AuthMode>("/auth/mode"),

  signup: (email: string, password: string) =>
    send<CredentialAuthResult>("POST", "/auth/signup", { email, password }),

  login: (email: string, password: string) =>
    send<CredentialAuthResult>("POST", "/auth/login", { email, password }),
};

export interface DeviceLinkView {
  userCode: string;
  clientLabel: string | null;
  status: string;
  expiresAt: string;
}

export const link = {
  get: (userCode: string) => http<DeviceLinkView>(`/api/link/${enc(userCode)}`),

  confirm: (userCode: string, body: { org_slug: string; project_slug: string }) =>
    send<{ status: string }>("POST", `/api/link/${enc(userCode)}/confirm`, body),

  deny: (userCode: string) =>
    send<{ status: string }>("POST", `/api/link/${enc(userCode)}/deny`),
};

/** Provider credentials are keyed by (org, provider), so they live here although Settings → Providers is a project route. */
export function orgApi(orgSlug: string) {
  const base = `/api/orgs/${enc(orgSlug)}`;

  return {
    base,

    listProviderCatalog: () => http<ProviderCatalogResponse>(`${base}/providers/catalog`),

    listProviderCredentials: () => http<ProviderCredentialListResponse>(`${base}/providers`),

    upsertProviderCredential: (provider: ModelProvider, body: UpsertProviderCredentialRequest) =>
      send<ProviderCredentialView>("PUT", `${base}/providers/${enc(provider)}`, body),

    deleteProviderCredential: (provider: ModelProvider) =>
      send<{ deleted: boolean }>("DELETE", `${base}/providers/${enc(provider)}`),
  };
}

export type OrgApi = ReturnType<typeof orgApi>;

export type TraceFilterParams = {
  kind?: string;
  fromTimestamp?: string;
  toTimestamp?: string;
  status?: string;
  q?: string;
  callSite?: string;
  hasCallSite?: boolean;
  detectedBy?: string;
  promptName?: string;
  /** Sent only with promptName; the server ignores it alone. */
  promptVersion?: string;
};

/** `name` is an exact match (at most one item); `folder` keeps the names under that prefix. */
export type PromptListParams = {
  q?: string;
  name?: string;
  folder?: string;
  label?: string;
  tag?: string;
  type?: PromptType;
  page?: number;
  limit?: number;
};

export function projectApi(orgSlug: string, projectSlug: string) {
  const base = `/api/orgs/${enc(orgSlug)}/projects/${enc(projectSlug)}`;

  return {
    base,

    getTriage: () => http<TriageView>(`${base}/cases`),
    getCase: (id: string) => http<CaseDetail>(`${base}/cases/${encodeURIComponent(id)}`),
    /** The detector's reference stays where it was, so this case's evidence is left out of it. */
    closeCase: (id: string) =>
      send<Case>("POST", `${base}/cases/${encodeURIComponent(id)}/resolve`, {}),
    /** Closes the case and moves the detector's reference, so the level it fired on becomes the new baseline. */
    absorbCase: (id: string) =>
      send<Case>("POST", `${base}/cases/${encodeURIComponent(id)}/absorb`),

    listAlertRules: () => http<AlertRule[]>(`${base}/alert-rules`),
    upsertAlertRule: (body: UpsertAlertRule) =>
      send<AlertRule>("PUT", `${base}/alert-rules`, body),
    setAlertRuleEnabled: (id: string, enabled: boolean) =>
      send<AlertRule>("PUT", `${base}/alert-rules/${encodeURIComponent(id)}/enabled`, { enabled }),
    listAlertChannels: () => http<AlertChannel[]>(`${base}/alert-channels`),
    createAlertChannel: (body: CreateAlertChannel) =>
      send<AlertChannel>("POST", `${base}/alert-channels`, body),
    deleteAlertChannel: (id: string) =>
      send<{ deleted: boolean }>("DELETE", `${base}/alert-channels/${encodeURIComponent(id)}`),

    getPipeline: () => http<PipelineEnvelope>(`${base}/pipeline`),

    listSources: () => http<IngestionSource[]>(`${base}/sources`),

    substrateStatus: () => http<SubstrateStatus>(`${base}/substrate/status`),

    onboarding: () => http<OnboardingProgress>(`${base}/onboarding`),

    listClassifiers: () => http<Classifier[]>(`${base}/classifiers`),
    setClassifierEnabled: (id: string, enabled: boolean) =>
      send<Classifier>("PUT", `${base}/classifiers/${enc(id)}/enabled`, { enabled }),
    /** `null` runs it on every call site again. */
    setClassifierCallSites: (id: string, callSiteIds: string[] | null) =>
      send<Classifier>("PUT", `${base}/classifiers/${enc(id)}/call-sites`, { call_site_ids: callSiteIds }),
    listClassifierCallSites: () => http<string[]>(`${base}/classifiers/call-sites`),
    listClassifierHealth: () => http<ClassifierHealth[]>(`${base}/classifiers/health`),
    /** 409s while the classifier sweeps. */
    resetClassifier: (id: string) =>
      send<Classifier>("POST", `${base}/classifiers/${enc(id)}/reset`),
    listClassifierEvents: (id: string, limit = 25) =>
      http<ClassifierEvent[]>(`${base}/classifiers/${enc(id)}/events?limit=${limit}`),
    getClassifierDailyVolume: (days = 7) =>
      http<ClassifierDailyVolume>(`${base}/classifiers/metrics/daily?days=${days}`),
    getClassifierChartScopes: (days: ChartRange = 28) =>
      http<ChartScopes>(`${base}/classifiers/chart-scopes?days=${days}`),
    getClassifierCharts: (scope: ChartScope, days: ChartRange = 28) =>
      http<ClassifierCharts>(withQuery(`${base}/classifiers/charts`, { ...scope, days })),
    getClassifierDebug: (id: string) => http<ClassifierDebug>(`${base}/classifiers/${enc(id)}/debug`),
    /** 422s for any classifier but groundedness. */
    getGroundednessStatus: (id: string) =>
      http<GroundednessStatus>(`${base}/classifiers/${enc(id)}/groundedness-status`),
    getClassifierTuning: (id: string) => http<ClassifierTuning>(`${base}/classifiers/${enc(id)}/tuning`),
    setClassifierTuning: (id: string, req: SetClassifierTuningRequest) =>
      send<ClassifierTuning>("PUT", `${base}/classifiers/${enc(id)}/tuning`, req),

    getVitals: (days = 7, by = "call_site") => http<Vitals>(`${base}/vitals?days=${days}&by=${enc(by)}`),

    /** The row limit is per call site, not sliced across all of them. */
    listBehaviorFindings: (
      detector?: string,
    ) =>
      http<BehaviorFindings>(
        `${base}/findings?status=open&include=all` + (detector ? `&detector=${enc(detector)}` : ""),
      ),

    getBehaviorFinding: (id: string) =>
      http<BehaviorFindingDetail>(`${base}/findings/${enc(id)}`),

    /** Paged rather than read off the finding: a tool-error cause can cite 27,000 refs. */
    getBehaviorFindingEvidence: (id: string, params?: { role?: string; limit?: number; cursor?: string }) =>
      http<EvidenceSpanPage>(
        withQuery(`${base}/findings/${enc(id)}/evidence`, {
          role: params?.role,
          limit: params?.limit,
          cursor: params?.cursor,
        }),
      ),

    getFrustratedSessions: (id: string, params?: CausePageParams) =>
      http<FrustratedSessionPage>(withQuery(`${base}/findings/${enc(id)}/frustrated-sessions`, causePage(params))),

    getFlaggedAnswers: (id: string, params?: CausePageParams) =>
      http<FlaggedAnswerPage>(withQuery(`${base}/findings/${enc(id)}/flagged-answers`, causePage(params))),

    /** `field` is a declared field's collapsed path (`items[].sku`), or `not_json` / `other`. */
    getMalformedOutputs: (id: string, field: string, params?: { limit?: number; cursor?: string }) =>
      http<MalformedOutputPage>(
        withQuery(`${base}/findings/${enc(id)}/malformed-outputs`, {
          field,
          limit: params?.limit,
          cursor: params?.cursor,
        }),
      ),

    /** 409s only when the finding cites no evidence at all. */
    analyzeBehaviorFinding: (id: string) =>
      send<BehaviorAnalysis>("POST", `${base}/findings/${enc(id)}/analysis`),

    resolveBehaviorFinding: (id: string, action: BehaviorResolutionAction) =>
      send<BehaviorFinding>("POST", `${base}/findings/${enc(id)}/resolution`, { action }),

    listTraces: (params?: {
      limit?: number;
      cursor?: string;
      kind?: string;
      fromTimestamp?: string;
      toTimestamp?: string;
      status?: string;
      q?: string;
      callSite?: string;
      hasCallSite?: boolean;
      /** A classifier id, or "any". */
      detectedBy?: string;
      promptName?: string;
      /** Sent only with promptName; the server ignores it alone. */
      promptVersion?: string;
      sort?: string;
    }) =>
      http<TracesPageView>(withQuery(`${base}/traces`, params)),

    /** A legacy v1 ULID from a bookmark or a Slack link still resolves, through the server's id map. */
    getTrace: (traceId: string) => http<TraceDetailView>(`${base}/traces/${enc(traceId)}`),

    // No sort parameter: ordering sessions by cost or tokens would sum every session before a page could be chosen.
    // A session is listed when one of its traces passes every filter.
    listSessions: (params?: { limit?: number; cursor?: string; include?: "totals" } & TraceFilterParams) =>
      http<SessionsPageView>(withQuery(`${base}/sessions`, params)),

    /** `matched_trace_ids` names the traces that pass the filters. */
    getSession: (sessionId: string, filters?: TraceFilterParams) =>
      http<SessionDetailView>(withQuery(`${base}/sessions/${enc(sessionId)}`, filters)),

    getSessionSpans: (sessionId: string) =>
      http<SessionSpansView>(`${base}/sessions/${enc(sessionId)}/spans`),

    createSource: (body: CreateSourceRequest) =>
      send<IngestionSource>("POST", `${base}/sources`, body),

    // No integration sends an empty body, and TanStack Query rejects an undefined result.
    getGitIntegration: () =>
      http<GitIntegration | null>(`${base}/git`).then((v) => v ?? null),
    getGithubInstallUrl: () => http<InstallUrl>(`${base}/git/github/install-url`),
    /** Reuses an installation already on the user's account (the 2nd+ project case). */
    getGithubAuthorizeUrl: () => http<InstallUrl>(`${base}/git/github/authorize-url`),
    /** The frontend auto-submits the returned `manifest` as a POSTed form field to `url`. */
    getGithubManifestStart: () => http<ManifestStart>(`${base}/git/github/manifest-url`),
    getGithubInstallationOptions: (token: string) =>
      http<InstallationOptions>(`${base}/git/github/installation-options?token=${enc(token)}`),
    selectGithubInstallation: (body: { token: string; installationId: number; repoOwner: string; repoName: string }) =>
      send<GitIntegration>("POST", `${base}/git/github/select-installation`, body),
    connectGit: (body: ConnectGitRequest) =>
      send<GitIntegration>("POST", `${base}/git/connect`, body),
    disconnectGit: () => send<{ deleted: boolean }>("DELETE", `${base}/git`),

    // "upsert" never deletes; "replace" removes entities missing from the upload.
    importEvalsDirectory: (files: File[], mode: ImportMode = "upsert") => {
      const form = new FormData();
      // The backend classifies each shard by its relative path.
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

    listMcpTokens: () => http<McpTokenView[]>(`${base}/mcp-tokens`),

    issueMcpToken: (name: string) =>
      send<IssueTokenResponse>("POST", `${base}/mcp-tokens`, { name }),

    revokeMcpToken: (tokenId: string) =>
      send<{ revoked: boolean }>("DELETE", `${base}/mcp-tokens/${enc(tokenId)}`),

    // Includes revoked keys.
    listApiKeys: () => http<ApiKey[]>(`${base}/api-keys`),

    createApiKey: (body: CreateKeyRequest) =>
      send<IssuedKeyResponse>("POST", `${base}/api-keys`, body),

    rotateApiKey: (keyId: string) =>
      send<IssuedKeyResponse>("POST", `${base}/api-keys/${enc(keyId)}/rotate`),

    revokeApiKey: (keyId: string) =>
      send<RevokeKeyResponse>("DELETE", `${base}/api-keys/${enc(keyId)}`),

    listApiKeyAudit: () => http<ApiKeyAudit[]>(`${base}/api-keys/audit`),

    /** Abort on a new query so a stale earlier response cannot race a newer one. */
    search: (q: string, signal?: AbortSignal) =>
      http<SearchResults>(`${base}/search?q=${enc(q)}`, { signal }),

    /** No body: only the finding id reaches the analysis lane. Re-pressing coalesces onto the existing report. */
    runCaseRca: (caseId: string) =>
      send<RcaReport>("POST", `${base}/cases/${enc(caseId)}/rca`),
    getRcaReport: (id: string) => http<RcaReport>(`${base}/rca/${enc(id)}`),
    /** Reports are immutable, so this returns a new report (or the running one, if it is still running). */
    rerunRca: (id: string) => send<RcaReport>("POST", `${base}/rca/${enc(id)}/rerun`),

    getModelSettings: () => http<ModelSettingsResponse>(`${base}/model-settings`),

    // Both mutations return the full view: a reset lane may land on a model only the server can name.
    setLaneModel: (lane: ModelLane, body: SetLaneModelRequest) =>
      send<ModelSettingsResponse>("PUT", `${base}/model-settings/${enc(lane)}`, body),

    resetLaneModel: (lane: ModelLane) =>
      send<ModelSettingsResponse>("DELETE", `${base}/model-settings/${enc(lane)}`),

    getRetention: () => http<RetentionView>(`${base}/retention`),

    updateRetention: (body: RetentionUpdateRequest) =>
      send<RetentionView>("PUT", `${base}/retention`, body),

    listRedactionRules: () => http<RedactionRuleListView>(`${base}/redaction/rules`),

    createRedactionRule: (body: UpsertRedactionRuleRequest) =>
      send<RedactionRuleView>("POST", `${base}/redaction/rules`, body),

    updateRedactionRule: (ruleId: string, body: UpsertRedactionRuleRequest) =>
      send<RedactionRuleView>("PUT", `${base}/redaction/rules/${enc(ruleId)}`, body),

    setRedactionRuleEnabled: (ruleId: string, enabled: boolean) =>
      send<RedactionRuleView>("PUT", `${base}/redaction/rules/${enc(ruleId)}/enabled`, { enabled }),

    deleteRedactionRule: (ruleId: string) =>
      send<null>("DELETE", `${base}/redaction/rules/${enc(ruleId)}`),

    previewRedaction: (body: RedactionPreviewRequest) =>
      send<RedactionPreviewView>("POST", `${base}/redaction/preview`, body),

    // Prompts. Routes take the prompt id, so a name with a / never meets a path segment.
    listPrompts: (params?: PromptListParams) => http<PromptPage>(withQuery(`${base}/prompts`, params)),
    /** Every prompt name with at least one call in the window; the list page joins it by name. */
    getPromptUsageSummary: (days?: number) =>
      http<PromptUsageSummary>(withQuery(`${base}/prompts/usage/summary`, { days })),
    /** 409 PROMPTS.NAME_EXISTS when the name is taken. */
    createPrompt: (body: CreatePromptRequest) => send<PromptDetail>("POST", `${base}/prompts`, body),
    getPrompt: (id: string) => http<PromptDetail>(`${base}/prompts/${enc(id)}`),
    updatePromptTags: (id: string, tags: string[]) =>
      send<PromptDetail>("PATCH", `${base}/prompts/${enc(id)}`, { tags }),
    deletePrompt: (id: string) => send<DeleteResponse>("DELETE", `${base}/prompts/${enc(id)}`),
    /** Deletes every prompt whose name sits under `path/`. */
    deletePromptFolder: (path: string) =>
      send<FolderDeleteResponse>("DELETE", withQuery(`${base}/prompts/folders`, { path })),
    getPromptVersion: (id: string, version: number) =>
      http<PromptVersionView>(`${base}/prompts/${enc(id)}/versions/${enc(version)}`),
    /** The server numbers the version; `labels` move to it. */
    createPromptVersion: (id: string, body: CreateVersionRequest) =>
      send<PromptVersionView>("POST", `${base}/prompts/${enc(id)}/versions`, body),
    /** 409 while the version carries a label, or when it is the only version. */
    deletePromptVersion: (id: string, version: number) =>
      send<DeleteResponse>("DELETE", `${base}/prompts/${enc(id)}/versions/${enc(version)}`),
    /** Replaces the version's labels: each listed label moves here, and an unlisted one leaves it. Never `latest`. */
    setPromptVersionLabels: (id: string, version: number, labels: string[]) =>
      send<PromptVersionView>("PUT", `${base}/prompts/${enc(id)}/versions/${enc(version)}/labels`, { labels }),
    getPromptUsage: (id: string, days?: number) =>
      http<PromptUsage>(withQuery(`${base}/prompts/${enc(id)}/usage`, { days })),
    /** One chat call on the org's own provider key; not recorded as a trace. */
    runPlayground: (body: PlaygroundRunRequest) =>
      send<PlaygroundRunView>("POST", `${base}/prompts/playground/runs`, body),

  };
}

export type ProjectApi = ReturnType<typeof projectApi>;
