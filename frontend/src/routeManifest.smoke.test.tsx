// SPDX-License-Identifier: Apache-2.0
/*
 * Route render smoke test: mounts the real <App/> in a MemoryRouter at every 'view' entry of the route manifest and
 * asserts it renders without throwing or an un-allowlisted console.error. Content is each view's own test's job.
 *
 * The real App picks its own provider stack per URL, so this cannot get that wrong. auth.me(), getCapabilities()
 * and listProjects() run on every mount and get realistic answers; FAKE_ME.orgs is the org list RootRedirect, Sidebar
 * and Link read.
 *
 * "Mounts" means the view's own Suspense fallback is gone and react-query is quiet, not just that the shell drew
 * text.
 *
 * Detail routes are reached with ids that 404, so the case, finding and RCA pages also mount once on real
 * payloads.
 *
 * Unlisted projectApi methods resolve undefined rather than a safe-empty default, so a capability key or API shape
 * that stopped resolving fails here instead of passing silently.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import App from "./App";
import { auth as mockedAuth } from "./api/client";
import { ThemeProvider } from "./ui/ThemeContext";
import { DensityProvider } from "./ui/density";
import { ApiError } from "./api/types";
import type {
  BehaviorFindingDetail,
  CaseDetail,
  EvidenceSpanPage,
  MalformedOutputDetail,
  MalformedOutputPage,
  RcaReport,
} from "./api/types";
import type { components } from "./api/generated/schema";
import type { CapabilityWire } from "./api/types-auth";
import manifest from "./routeManifest.generated.json";
import { GROUNDEDNESS_FINDING_DETAIL } from "./test/groundednessFixtures";

// ---- api/client mock -------------------------------------------------------------------------

// Mirrors CapabilityService.OFF_BY_DEFAULT: every wire key is on except this one.
const OFF_IN_OPEN_EDITION: CapabilityWire[] = ["triage_automatic_enabled"];
const ALL_CAPABILITY_KEYS: CapabilityWire[] = [
  "ci_integration_enabled",
  "rca_enabled",
  "api_access_enabled",
  "alerts_enabled",
  "slack_enabled",
  "custom_redaction_enabled",
  "byo_provider_keys_enabled",
  "duration_drift_enabled",
  "cost_drift_enabled",
  "tool_error_enabled",
  "frustration_enabled",
  "groundedness_enabled",
  "secret_leak_enabled",
  "malformed_output_enabled",
  "triage_automatic_enabled",
];
const OPEN_EDITION_CAPABILITIES: Record<CapabilityWire, boolean> = Object.fromEntries(
  ALL_CAPABILITY_KEYS.map((k) => [k, !OFF_IN_OPEN_EDITION.includes(k)]),
) as Record<CapabilityWire, boolean>;

const FAKE_ME = {
  id: "user-fake",
  email: "smoke@example.com",
  // RootRedirect, Sidebar and Link read the org list off GET /auth/me; an empty list would redirect every route to
  // /login.
  orgs: [{ id: "org-fake", slug: "fake-orgSlug", name: "Fake Org", role: "owner" }],
  platform_staff: false,
};
const FAKE_ORG = {
  id: "org-fake",
  workos_org_id: null,
  slug: "fake-orgSlug",
  name: "Fake Org",
  created_at: "2026-01-01T00:00:00Z",
  archived_at: null,
  settings: null,
};
const FAKE_PROJECT = {
  id: "project-fake",
  org_id: "org-fake",
  slug: "fake-projectSlug",
  name: "Fake Project",
  description: null,
  created_at: "2026-01-01T00:00:00Z",
  archived_at: null,
  settings: null,
  is_default: true,
};

/** Never invoked at mount; a stray call fails with a clear message. */
function unusedMutation(name: string) {
  return vi.fn(() => Promise.reject(new Error(`auth.${name} was not expected to run during a route smoke test`)));
}

vi.mock("./api/client", () => {
  const auth = {
    me: vi.fn(() => Promise.resolve(FAKE_ME)),
    getCapabilities: vi.fn(() => Promise.resolve({ capabilities: OPEN_EDITION_CAPABILITIES })),
    listProjects: vi.fn(() => Promise.resolve([FAKE_PROJECT])),
    getOrg: vi.fn(() => Promise.resolve(FAKE_ORG)),
    listMembers: vi.fn(() => Promise.resolve([])),
    listInvitations: vi.fn(() => Promise.resolve([])),
    loginUrl: vi.fn((returnTo?: string) => `/auth/login${returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ""}`),
    // Login and Signup call this on mount; unresolved, both hang on the loading screen.
    mode: vi.fn(() => Promise.resolve({ redirectFlow: false, firstRun: false })),
    login: unusedMutation("login"),
    signup: unusedMutation("signup"),
    logout: unusedMutation("logout"),
    addMember: unusedMutation("addMember"),
    revokeInvitation: unusedMutation("revokeInvitation"),
    updateMember: unusedMutation("updateMember"),
    removeMember: unusedMutation("removeMember"),
    createProject: unusedMutation("createProject"),
    updateOrg: unusedMutation("updateOrg"),
    makeProjectDefault: unusedMutation("makeProjectDefault"),
    archiveProject: unusedMutation("archiveProject"),
    unarchiveProject: unusedMutation("unarchiveProject"),
    deleteProject: unusedMutation("deleteProject"),
  };
  const link = {
    get: unusedMutation("link.get"),
    confirm: unusedMutation("link.confirm"),
    deny: unusedMutation("link.deny"),
  };
  // Unlisted methods resolve undefined, never a safe-empty shape (see the header).
  function projectApi(orgSlug: string, projectSlug: string) {
    const overrides = currentProjectApiOverrides;
    return new Proxy(
      { base: `/api/orgs/${orgSlug}/projects/${projectSlug}` },
      {
        get(target, prop: string | symbol) {
          if (typeof prop !== "string") return undefined;
          if (prop in overrides) return overrides[prop];
          const own = (target as Record<string, unknown>)[prop];
          return own ?? (() => Promise.resolve(undefined));
        },
      },
    );
  }
  // Provider credentials are org-scoped; Settings > Providers calls this on mount.
  function orgApi(orgSlug: string) {
    const overrides = currentOrgApiOverrides;
    return new Proxy(
      { base: `/api/orgs/${orgSlug}` },
      {
        get(target, prop: string | symbol) {
          if (typeof prop !== "string") return undefined;
          if (prop in overrides) return overrides[prop];
          const own = (target as Record<string, unknown>)[prop];
          return own ?? (() => Promise.resolve(undefined));
        },
      },
    );
  }
  return { auth, link, projectApi, orgApi };
});

// The active test's overrides, read lazily by the projectApi/orgApi factories after the module mock is wired.
// eslint-disable-next-line prefer-const
let currentProjectApiOverrides: Record<string, unknown> = {};
// eslint-disable-next-line prefer-const
let currentOrgApiOverrides: Record<string, unknown> = {};

// ---- console.error allowlist ------------------------------------------------------------------

// Known-benign jsdom and MemoryRouter noise, inventoried from a real run. Anything else fails the test it fires in.
const CONSOLE_ERROR_ALLOWLIST: RegExp[] = [
  // React Router v7's relative-splat warning, which some versions route through console.error.
  /Relative route resolution/i,
];

function isAllowlisted(args: unknown[]): boolean {
  const message = args.map((a) => (typeof a === "string" ? a : String(a))).join(" ");
  return CONSOLE_ERROR_ALLOWLIST.some((re) => re.test(message));
}

// ---- param substitution -----------------------------------------------------------------------

type ManifestEntry = { path: string; kind: "view" | "redirect"; fullPath: string };
const ENTRIES = manifest as ManifestEntry[];
const VIEWS = ENTRIES.filter((e) => e.kind === "view");

/** Every `:paramName` in the manifest, mapped mechanically to `fake-<name>`. */
const PARAM_NAMES = Array.from(
  new Set(VIEWS.flatMap((v) => Array.from(v.fullPath.matchAll(/:(\w+)/g)).map((m) => m[1]))),
);
const FIXED_PARAM_VALUES: Record<string, string> = Object.fromEntries(
  PARAM_NAMES.map((name) => [name, `fake-${name}`]),
);
// The tenant params double as the fake org and project slugs, so a view cross-checking URL against fetched slug stays
// consistent.
if (FIXED_PARAM_VALUES.orgSlug !== FAKE_ORG.slug || FIXED_PARAM_VALUES.projectSlug !== FAKE_PROJECT.slug) {
  throw new Error("routeManifest.smoke.test.tsx: FAKE_ORG/FAKE_PROJECT slugs must match FIXED_PARAM_VALUES");
}

function resolveUrl(fullPath: string): string {
  return fullPath.replace(/:(\w+)/g, (_, name: string) => FIXED_PARAM_VALUES[name]);
}

// ---- per-route projectApi overrides ------------------------------------------------------------

/**
 * What the backend really answers for a `fake-<param>` id: a 404 ApiError, so detail views render their not-found
 * state deterministically. Resolving undefined is a shape the endpoint never produces.
 */
const NOT_FOUND = (resource: string) => () =>
  Promise.reject(new ApiError(404, { code: `${resource}.not_found`, message: `no ${resource} with that id` }));

/** A genuinely empty list is a real response for these endpoints. */
const EMPTY_TRIAGE = {
  cases: [],
  muted: [],
  recently_resolved: [],
  // Triage's all-clear state reads this without `?.`.
  watching: { classifiers: 0, call_sites: 0, traces_last_day: 0, traces_total: 0, open_findings: 0 },
};
const EMPTY = () => Promise.resolve([]);

/**
 * The full `Vitals` shape: a partial fixture once passed only because the suite stopped before PulseStrip read
 * `total.cost`.
 */
const EMPTY_GROUP = { cost: { usd: 0, delta_pct_per_turn: null, baseline_usd: null, calls: 0, unpriced_calls: 0, tokens: 0, flagged: false }, duration: { p50_ms: 0, p95_ms: 0, delta_pct: null, baseline_p95_ms: null, turns: 0, flagged: false }, flagged: false, key: null, label: null };
const EMPTY_VITALS = {
  dimension: "call_site",
  groups: [] as unknown[],
  priced_models: 0,
  total: EMPTY_GROUP,
  window: { from: "2026-01-01T00:00:00Z", to: "2026-01-08T00:00:00Z", days: 7, baseline_from: "2025-12-25T00:00:00Z", baseline_to: "2026-01-01T00:00:00Z" },
};

/** `useOnboarding` reads `.stage` without `?.`. */
const EMPTY_ONBOARDING = {
  stage: "watching" as const,
  listening: true,
  cases: 0,
  findings: 0,
  triaged_cases: 0,
  first_trace_at: null,
  last_trace_at: null,
  first_finding_at: null,
  first_triaged_case_at: null,
  baseline_buckets: 0,
  baseline_buckets_armed: 0,
  baseline_min_sample: 0,
  baseline_samples_in_flight: 0,
  baseline_best_window_count: 0,
};

/** A connected project: without it ProjectShell mounts ConnectGate in place of every project-scoped view. */
const CONNECTED_SUBSTRATE_STATUS = {
  has_live: true,
  untagged_spans: 0,
  has_tagged_span: true,
  spans_received: 1,
  tagged_spans: 1,
  last_span_at: "2026-01-01T00:00:00Z",
  service_name: "smoke-test",
};

/** Envelope-shaped: a bare `[]` would be the wrong-but-plausible shape this suite exists to catch. */
const EMPTY_PROVIDER_CATALOG = { models: [] as unknown[], platforms: [] as unknown[] };
const EMPTY_PROVIDER_CREDENTIALS = { credentials: [] as unknown[] };
const EMPTY_MODEL_SETTINGS = { groups: [] as unknown[], lanes: [] as unknown[], models: [] as unknown[], settings: [] as unknown[], configured_providers: [] as unknown[] };
const EMPTY_REDACTION_RULES = { rules: [] as unknown[] };
const EMPTY_SESSION_SPANS = { spans: [] as unknown[], spans_truncated: false };

/**
 * Applied to every tenant route. ShellChrome's nav badge reads getTriage; CapabilityGate briefly mounts Triage
 * (getVitals, onboarding) while its read loads; ProjectShell reads substrateStatus before anything mounts. Unmocked,
 * each logs "data cannot be undefined".
 */
const SHELL_CHROME_OVERRIDES: Record<string, () => Promise<unknown>> = {
  getTriage: () => Promise.resolve(EMPTY_TRIAGE),
  getVitals: () => Promise.resolve(EMPTY_VITALS),
  onboarding: () => Promise.resolve(EMPTY_ONBOARDING),
  substrateStatus: () => Promise.resolve(CONNECTED_SUBSTRATE_STATUS),
  // Triage reads `configured_providers` during that same transient mount.
  getModelSettings: () => Promise.resolve(EMPTY_MODEL_SETTINGS),
};

/** Keyed by the manifest's raw `path`. */
const VIEW_OVERRIDES: Record<string, Record<string, () => Promise<unknown>>> = {
  "cases/:caseId": {
    getCase: NOT_FOUND("case"),
    // The case header offers "Connect repository" when the project has none.
    getGitIntegration: () => Promise.resolve(null),
  },
  sources: {
    listSources: EMPTY,
  },
  traces: {
    // The real `TracesPage` shape: `useObservedFacets` reads `p.traces` without `?.`.
    listTraces: () => Promise.resolve({ traces: [], next_cursor: null }),
  },
  "traces/:traceId": {
    getTrace: NOT_FOUND("trace"),
  },
  // SessionDetail issues both reads on mount; spans come in their own envelope.
  "sessions/:sessionId": {
    getSession: NOT_FOUND("session"),
    getSessionSpans: () => Promise.resolve(EMPTY_SESSION_SPANS),
  },
  classifiers: {
    listClassifiers: EMPTY,
    listBehaviorFindings: EMPTY,
    getTriage: () => Promise.resolve(EMPTY_TRIAGE),
  },
  "classifiers/detectors": {
    listClassifiers: EMPTY,
    getClassifierDailyVolume: EMPTY,
    listClassifierHealth: EMPTY,
  },
  // A real groundedness finding, so the whole story renders rather than only not-found.
  "classifiers/findings/:findingId": {
    getBehaviorFinding: () => Promise.resolve(GROUNDEDNESS_FINDING_DETAIL),
  },
  vitals: {
    getVitals: () => Promise.resolve(EMPTY_VITALS),
  },
  // Legacy `/pipeline/*` still resolves to a view (triage), so it needs triage's queries.
  "pipeline/*": {
    getPipeline: () => Promise.resolve(null),
    getTriage: () => Promise.resolve(EMPTY_TRIAGE),
  },
  "rca/:reportId": {
    getRcaReport: NOT_FOUND("rca report"),
    // The "analyzed without repository access" notice reads this too.
    getGitIntegration: () => Promise.resolve(null),
  },
  // Settings sections each read at least one endpoint on mount and need its real empty shape.

  // `/settings` redirects to Sources, so it issues Sources' read.
  settings: {
    listSources: EMPTY,
  },
  import: {
    listSources: EMPTY,
    getGitIntegration: () => Promise.resolve(null),
  },
  // The client coalesces an empty body to `null`: a real "no repo connected" answer.
  git: {
    getGitIntegration: () => Promise.resolve(null),
  },
  models: {
    getModelSettings: () => Promise.resolve(EMPTY_MODEL_SETTINGS),
  },
  notifications: {
    listAlertRules: EMPTY,
    listAlertChannels: EMPTY,
  },
  "mcp-tokens": {
    listMcpTokens: EMPTY,
  },
  "api-keys": {
    listApiKeys: EMPTY,
    listApiKeyAudit: EMPTY,
  },
  "pii-redaction": {
    listRedactionRules: () => Promise.resolve(EMPTY_REDACTION_RULES),
  },
  retention: {
    getRetention: () =>
      Promise.resolve({
        classes: [
          { data_class: "traces", ttl_days: 90, from_policy: false, platform_default_days: 90 },
          { data_class: "detections", ttl_days: 90, from_policy: false, platform_default_days: 90 },
        ],
        can_manage: true,
      }),
  },
  // "*" is both the project catch-all (Triage) and Settings' catch-all (Sources); listSources matters only to the
  // second.
  "*": {
    listSources: EMPTY,
  },
};

// Provider credentials are org-scoped, so the one route that reads them gets its own map.
const VIEW_ORG_OVERRIDES: Record<string, Record<string, () => Promise<unknown>>> = {
  providers: {
    listProviderCatalog: () => Promise.resolve(EMPTY_PROVIDER_CATALOG),
    listProviderCredentials: () => Promise.resolve(EMPTY_PROVIDER_CREDENTIALS),
  },
};

// Shapes follow the generated schema types, so an API rename fails `tsc` here.

const T0 = "2026-01-06T10:00:00Z";
const T1 = "2026-01-06T12:00:00Z";

const SECRET_LEAK: components["schemas"]["SecretLeakDetail"] = {
  basis: "Any high-confidence match opens a case.",
  confidence: "high",
  firstAt: T0,
  lastAt: T1,
  keys: [{ lastAt: T1, leaks: 2, masked: "sk-a…9f2c", storedRaw: true, traces: 2 }],
  leakCount: 2,
  leaks: [
    { at: T0, masked: "sk-a…9f2c", spanId: "span-1", stored: "raw", traceId: "trace-1" },
    { at: T1, masked: "sk-a…9f2c", spanId: "span-2", stored: "redacted", traceId: "trace-2" },
  ],
  rule: "openai_api_key",
  threshold: 1,
  traceCount: 2,
  windowEnd: T1,
  windowSeconds: 86400,
  windowStart: "2026-01-05T12:00:00Z",
};

const MALFORMED_OUTPUT: MalformedOutputDetail = {
  fields: [
    { depth: 0, failing: 0, name: "items", path: "items", required: true, type: "array" },
    { depth: 1, failing: 14, name: "sku", path: "items[].sku", required: true, type: "string" },
  ],
  notJson: 3,
  other: 0,
  rate: {
    bucketKey: "extract.order",
    criticality: 0.9,
    curRate: 0.17,
    deltaPp: 15,
    direction: "up",
    effectSize: 0.6,
    failingTraces: ["trace-3"],
    failuresCur: 17,
    nCur: 100,
    nRef: 400,
    onsetAt: T0,
    patterns: [],
    patternsTruncated: false,
    refRate: 0.02,
    statistic: 5.1,
    threshold: 3,
    windowClosedAt: T1,
    windowOpenedAt: "2026-01-05T12:00:00Z",
  },
};

const MALFORMED_OUTPUTS: MalformedOutputPage = {
  nextCursor: null,
  total: 1,
  rows: [
    {
      document: '{"items":[{"qty":2}]}',
      highlightLines: [1],
      message: "items[0].sku is required",
      name: "extract_order",
      spanId: "span-3",
      startedAt: T0,
      traceId: "trace-3",
    },
  ],
};

const WITNESS_EVIDENCE: EvidenceSpanPage = {
  counts: { witness: 1 },
  recordedCounts: { witness: 1 },
  nextCursor: null,
  rows: [
    {
      callSiteId: "extract.order",
      errorType: null,
      inputPreview: "order #1182",
      kind: "llm",
      latencyMs: 820,
      level: null,
      models: ["gpt-5"],
      name: "extract_order",
      notRolledUp: false,
      outputPreview: '{"items":[{"qty":2}]}',
      partialCost: false,
      rank: 1,
      role: "witness",
      secretKey: null,
      sessionId: null,
      spanId: "span-3",
      staleTotals: false,
      startedAt: T0,
      status: "ok",
      storedAs: null,
      totalCost: 0.002,
      totalTokens: 900,
      traceId: "trace-3",
      violation: null,
    },
  ],
};

function detailCase(detector: string, title: string): CaseDetail["case"] {
  return {
    baseline_value: null,
    basis: "Measured against the rate fitted over the prior week.",
    call_site_id: "extract.order",
    cause: null,
    current_value: null,
    delta: null,
    detector,
    disposition: null,
    finding_count: 1,
    id: "case-1",
    last_seen_at: T1,
    latest_finding_id: "finding-1",
    locked_at: null,
    metric: detector,
    muted_at: null,
    muted_by: null,
    onset_at: T0,
    opened_at: T0,
    rca_verdict: null,
    reference: "CASE-7",
    resolution: null,
    resolution_reason: null,
    resolved_at: null,
    resolved_by: null,
    severity: 3,
    state: "open",
    subject_id: "extract.order",
    subject_kind: "call_site",
    subject_label: "extract.order",
    title,
  };
}

function caseDetail(over: Partial<CaseDetail> & Pick<CaseDetail, "case">): CaseDetail {
  return {
    absorb_available: false,
    detector_available: true,
    events: [{ actor: null, created_at: T0, id: "ev-1", kind: "opened", summary: "Case opened" }],
    exemplars: [],
    frustration: null,
    groundedness: null,
    latest_finding_id: "finding-1",
    malformed_output: null,
    metric: null,
    rca: null,
    rca_available: true,
    rca_report_id: null,
    ruling: null,
    secret_leak: null,
    tool_error: null,
    ...over,
  };
}

function findingDetail(
  detector: string,
  causeKind: BehaviorFindingDetail["finding"]["causeKind"],
  over: Partial<BehaviorFindingDetail>,
): BehaviorFindingDetail {
  return {
    armedWindow: null,
    frustration: null,
    groundedness: null,
    malformedOutput: null,
    metric: null,
    secretLeak: null,
    toolError: null,
    finding: {
      callSiteId: "extract.order",
      caseId: "case-1",
      causeKey: `${detector}:extract.order`,
      causeKind,
      detector,
      firstSeenAt: T0,
      humanVerdictAt: null,
      id: "finding-1",
      lastSeenAt: T1,
      status: "open",
      title: `${detector} finding`,
      traceCount: 2,
      triageAction: null,
      triageCitations: [],
      triageStatus: "pending",
      triageSummary: null,
      triageVerdict: null,
      triagedAt: null,
      workflowKey: "extract.order",
    },
    ...over,
  };
}

const FINISHED_RCA: RcaReport = {
  call_site_id: "extract.order",
  causes: [],
  completed_at: T1,
  created_at: T0,
  current_value: 0.17,
  delta: -0.15,
  detailed_report: "The order extractor dropped `sku` after the prompt change.",
  engine: "agentic",
  hypotheses: [
    { confidence: "high", evidence_trace_ids: ["trace-3"], rationale: "Every failure follows the prompt change.", title: "Prompt change" },
  ],
  id: "rca-1",
  job_id: "job-1",
  metric: "tool_error_rate",
  prior_value: 0.02,
  repo_available: true,
  report_kind: "degradation",
  ruled_out: [{ assessment: "ruled_out", check: "traffic_mix", detail: "Traffic mix unchanged.", measurement: null, passed: true }],
  status: "done",
  subject_id: "extract.order",
  subject_kind: "call_site",
  subject_label: "extract.order",
  summary: "The prompt change dropped the sku field.",
  verdict: "behavior_change",
  window_from: "2026-01-05T10:00:00Z",
  window_split: T0,
  window_to: T1,
};

const resolved = <T,>(value: T) => () => Promise.resolve(value);

/** One detail route on a real payload: its manifest entry, the reads it answers, and the heading it must draw. */
const DETAIL_FIXTURES: {
  name: string;
  path: string;
  overrides: Record<string, () => Promise<unknown>>;
  heading: RegExp;
}[] = [
  {
    name: "a secret-leak case",
    path: "cases/:caseId",
    overrides: {
      getCase: resolved(
        caseDetail({ case: detailCase("secret_leak", "OpenAI key in extract.order output"), secret_leak: SECRET_LEAK }),
      ),
      getBehaviorFindingEvidence: resolved(WITNESS_EVIDENCE),
    },
    heading: /OpenAI key in extract\.order output/,
  },
  {
    name: "a malformed-output case",
    path: "cases/:caseId",
    overrides: {
      getCase: resolved(
        caseDetail({
          case: detailCase("malformed_output", "extract.order outputs failing their schema"),
          malformed_output: MALFORMED_OUTPUT,
        }),
      ),
      getBehaviorFindingEvidence: resolved(WITNESS_EVIDENCE),
      getMalformedOutputs: resolved(MALFORMED_OUTPUTS),
    },
    heading: /extract\.order outputs failing their schema/,
  },
  {
    name: "a secret-leak finding",
    path: "classifiers/findings/:findingId",
    overrides: {
      getBehaviorFinding: resolved(findingDetail("secret_leak", "armed_window", { secretLeak: SECRET_LEAK })),
      getBehaviorFindingEvidence: resolved(WITNESS_EVIDENCE),
    },
    heading: /openai_api_key in extract\.order output/,
  },
  {
    name: "a malformed-output finding",
    path: "classifiers/findings/:findingId",
    overrides: {
      getBehaviorFinding: resolved(findingDetail("malformed_output", "malformed_rate", { malformedOutput: MALFORMED_OUTPUT })),
      getBehaviorFindingEvidence: resolved(WITNESS_EVIDENCE),
      getMalformedOutputs: resolved(MALFORMED_OUTPUTS),
    },
    heading: /Schema failure rate 2\.00% → 17\.00%/,
  },
  {
    name: "a finished RCA report",
    path: "rca/:reportId",
    overrides: { getRcaReport: resolved(FINISHED_RCA) },
    heading: /RCA \(root-cause analysis\): extract\.order/,
  },
];

// ---- rendering ----------------------------------------------------------------------------------

function renderApp(url: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <DensityProvider className="contents">
          <MemoryRouter initialEntries={[url]}>
            <App />
          </MemoryRouter>
        </DensityProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return { ...view, queryClient };
}

// Eagerly import every view once. Behind a MemoryRouter the production preload never fires, so a cold lazy import
// could still be in flight when the page looks settled. Test files are excluded: importing one would run its
// vi.mock() calls here.
const VIEW_MODULES = import.meta.glob(["./views/**/*.tsx", "!./views/**/*.test.tsx"]);

/** Mounts the real App at `url` and waits until the view has settled; throws if it never does. */
async function mountAndSettle(url: string): Promise<HTMLElement> {
  const { container, queryClient } = renderApp(url);

  await waitFor(
    () => {
      expect(container.textContent?.trim().length ?? 0).toBeGreaterThan(0);
    },
    { timeout: 5000 },
  );
  // Settled means the view's Suspense fallback ("Loading view") is gone, react-query is idle, and the query cache has
  // not changed for QUIET_MS; one idle snapshot can fall between two state changes of one query. Each poll is its own
  // short act() scope: React flushes queued work, including lazy imports, as a scope exits, and one long waitFor-
  // style act parked the imports so most routes asserted against a spinner.
  const QUIET_MS = 250;
  const TIMEOUT_MS = 5000;
  const snapshotQueries = () =>
    JSON.stringify(
      queryClient
        .getQueryCache()
        .getAll()
        .map((q) => [q.queryHash, q.state.status, q.state.fetchStatus])
        .sort(),
    );
  let lastSnapshot = snapshotQueries();
  let quietSince = Date.now();
  const deadline = Date.now() + TIMEOUT_MS;
  for (;;) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    const suspended = container.querySelector('[aria-label="Loading view"]') !== null;
    const busy = queryClient.isFetching() > 0 || queryClient.isMutating() > 0;
    const snapshot = snapshotQueries();
    if (suspended || busy || snapshot !== lastSnapshot) {
      lastSnapshot = snapshot;
      quietSince = Date.now();
    }
    if (!suspended && Date.now() - quietSince >= QUIET_MS) break;
    if (Date.now() > deadline) {
      throw new Error(
        `route never settled while mounting ${url} (view still suspended: ${suspended}, react-query busy: ${busy})`,
      );
    }
  }
  return container;
}

describe("route manifest render smoke test", () => {
  beforeAll(async () => {
    await Promise.all(Object.values(VIEW_MODULES).map((load) => load()));
  });

  it("has a distinct fullPath for every view entry (proves the collision fix, not just trusts it)", () => {
    const fullPaths = VIEWS.map((v) => v.fullPath);
    expect(new Set(fullPaths).size).toBe(fullPaths.length);
  });

  let consoleErrorSpy: ReturnType<typeof vi.spyOn>;
  const unexpectedErrors: string[] = [];

  beforeEach(() => {
    currentProjectApiOverrides = {};
    currentOrgApiOverrides = {};
    unexpectedErrors.length = 0;
    consoleErrorSpy = vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
      if (!isAllowlisted(args)) {
        unexpectedErrors.push(args.map((a) => (typeof a === "string" ? a : String(a))).join(" "));
      }
    });
  });

  afterEach(() => {
    consoleErrorSpy.mockRestore();
    cleanup();
  });

  for (const view of VIEWS) {
    const url = resolveUrl(view.fullPath);
    it(`mounts ${view.fullPath} (raw path "${view.path}") -> ${url}`, async () => {
      currentProjectApiOverrides = { ...SHELL_CHROME_OVERRIDES, ...(VIEW_OVERRIDES[view.path] ?? {}) };
      currentOrgApiOverrides = { ...(VIEW_ORG_OVERRIDES[view.path] ?? {}) };

      await mountAndSettle(url);

      expect(unexpectedErrors, `unexpected console.error while mounting ${url}:\n${unexpectedErrors.join("\n")}`).toEqual([]);
    });
  }

  // Bug: a crash in the success render of the case, finding or RCA page ships green, because the loop reaches them
  // only with ids that 404.
  for (const fixture of DETAIL_FIXTURES) {
    const view = VIEWS.find((v) => v.path === fixture.path);
    it(`mounts ${fixture.path} on ${fixture.name} and draws its heading`, async () => {
      if (!view) throw new Error(`no manifest view with raw path ${fixture.path}`);
      const url = resolveUrl(view.fullPath);
      currentProjectApiOverrides = { ...SHELL_CHROME_OVERRIDES, ...VIEW_OVERRIDES[fixture.path], ...fixture.overrides };

      const container = await mountAndSettle(url);

      within(container).getByRole("heading", { level: 1, name: fixture.heading });
      expect(unexpectedErrors, `unexpected console.error while mounting ${url}:\n${unexpectedErrors.join("\n")}`).toEqual([]);
    });
  }

  // The switcher renders on every page, so an Organizations section here would be far more visible than NewOrg ever
  // was.
  it("sidebar switcher has no Organizations section or New-organization action", async () => {
    currentProjectApiOverrides = { ...SHELL_CHROME_OVERRIDES };
    const url = resolveUrl("/orgs/:orgSlug/projects/:projectSlug/triage");
    const { container } = renderApp(url);

    // Wait for the switcher itself: a text check passes on ProjectShell's transient loading screen.
    await waitFor(() => {
      expect(container.querySelector('button[aria-haspopup="menu"]'), "project switcher button not found").toBeTruthy();
    });
    const switcherButton = container.querySelector('button[aria-haspopup="menu"]');
    // Click opens the dropdown reliably; a synthetic mouseenter does not.
    (switcherButton as HTMLButtonElement).click();

    await waitFor(() => {
      // Projects must render, so a missing Organizations heading means no section, not an unopened menu.
      expect(container.textContent).toContain("Projects");
    });

    expect(container.textContent).not.toContain("Organizations");
    expect(container.textContent).not.toContain("New organization");
  });
});

/*
 * Where the app sends a visitor before any view mounts. Bugs: a signed-in user with no organization left on a blank
 * screen, an org's only real project confused with its sample, and a project with no tagged span shown the shell
 * instead of the connect gate.
 */
describe("app entry redirects", () => {
  afterEach(() => {
    cleanup();
    currentProjectApiOverrides = {};
  });

  // Not to /login: that sends a signed-in visitor back to the root, which sent them there, forever.
  it("tells a signed-in user who belongs to no organization so, and lets them sign out", async () => {
    vi.mocked(mockedAuth.me).mockResolvedValueOnce({ ...FAKE_ME, orgs: [] });
    const { container } = renderApp("/");

    await waitFor(() => expect(container.textContent).toContain("not a member of any organization"));
    // Signing in again mints a personal organization, so an invitation is not what they need.
    expect(container.textContent).toContain("Sign out and sign in again to get your own organization.");
    expect(mockedAuth.mode).not.toHaveBeenCalled();

    vi.mocked(mockedAuth.logout).mockResolvedValueOnce({ frontendUrl: "about:blank" });
    within(container).getByRole("button", { name: "Sign out" }).click();
    await waitFor(() => expect(mockedAuth.logout).toHaveBeenCalled());
  });

  it("sends an organization whose only project is the sample to create a real one", async () => {
    vi.mocked(mockedAuth.listProjects).mockResolvedValueOnce([{ ...FAKE_PROJECT, deleting_at: null, settings: '{"sample": true}' }]);
    const { container } = renderApp("/orgs/fake-orgSlug");

    await waitFor(() => expect(container.textContent).toContain("New project"));
  });

  it("puts a project with no tagged span behind the connect gate", async () => {
    currentProjectApiOverrides = {
      ...SHELL_CHROME_OVERRIDES,
      substrateStatus: () => Promise.resolve({ ...CONNECTED_SUBSTRATE_STATUS, has_tagged_span: false, tagged_spans: 0 }),
    };
    const { container } = renderApp(resolveUrl("/orgs/:orgSlug/projects/:projectSlug/triage"));

    await waitFor(() => expect(container.textContent).toContain("Connect your traces"));
    expect(container.querySelector('button[aria-haspopup="menu"]')).toBeNull();
  });
});
