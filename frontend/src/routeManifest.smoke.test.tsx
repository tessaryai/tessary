// SPDX-License-Identifier: Apache-2.0
/*
 * Route render smoke test.
 *
 * Mounts the real <App/> (wrapped as src/main.tsx wraps it, with BrowserRouter swapped for a
 * MemoryRouter pointed at one URL) at every 'view' entry in the regenerated route manifest, and
 * asserts each one renders something without throwing. This is a render-smoke test, not a content
 * test: the only assertion per route is that the container is non-blank and nothing threw. A
 * view's actual behavior is that view's own test's job.
 *
 * WHY MOUNT THE REAL APP rather than hand-select a provider stack per route: App.tsx itself
 * decides which of TenantProvider/CapabilityGate/ProtectedRoute apply to a given URL (some view
 * entries must NOT be wrapped in TenantProvider, which needs the tenant prefix's params — see
 * TenantContext.tsx). Mounting the real App and letting its own <Routes> tree pick
 * the wrapper avoids getting that call wrong here.
 *
 * MOCKING STRATEGY. auth.me()/getCapabilities()/listProjects() are called
 * unconditionally on mount (AuthProvider, useCapabilities, the shell chrome's
 * sidebar) — these get fixed, realistic answers below so every route gets past the loading screen.
 * FAKE_ME.orgs stands in for the org list: RootRedirect, Sidebar, and Link all read it off
 * GET /auth/me. Every other `auth` method is a mutation, never invoked at mount, so it is stubbed
 * to a rejected-never-called shape only for type completeness.
 *
 * WHAT "MOUNTS" MEANS HERE. Every view is React.lazy'd behind ProjectShell's own <Suspense>, whose
 * fallback sits inside the shell chrome — so a container that merely has text proves the chrome
 * rendered, not the view. Each route's settle loop below waits for that fallback to go away as
 * well as for react-query to go quiet, and fails the route if it never does.
 *
 * DETAIL PAGES WITH REAL DATA. The manifest loop reaches every detail route with an id that does not
 * exist, so it only ever renders the not-found branch. The case, finding and RCA pages also mount
 * once each on a real payload (a secret-leak and a malformed-output case and finding, and a finished
 * RCA report), so a crash in their success render fails here instead of shipping.
 *
 * `projectApi(...)` is different, deliberately: each method a test case does not explicitly
 * override defaults to `Promise.resolve(undefined)` via a Proxy, not a safe-empty default. This
 * suite's whole purpose is catching a capability key or API shape that silently stopped resolving
 * — a safe-empty default (`[]`/`{}`) would let exactly that regression pass silently.
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

// Capability defaults mirror CapabilityService.OFF_BY_DEFAULT
// (backend/product/src/main/java/ai/tessary/plan/CapabilityService.java): every wire key is true
// except this one.
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
  // GET /api/me/orgs is gone; RootRedirect/Sidebar/Link all read the org list off GET /auth/me
  // instead, so this fixture (not a mocked listMyOrgs()) is what stands between every one of
  // those routes and an unwanted redirect to /login on an empty list. TenantService#ensureDefaultOrg
  // guarantees the list is never actually empty post-auth.
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

/** Never actually invoked at mount — see the header note — kept only so a stray call fails loudly
 * with a clear message rather than "not a function". */
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
    // Called unconditionally on mount by Login/Signup -- must resolve, same as
    // me()/getCapabilities() above, or those two routes hang on the loading screen forever.
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
  // See the header note: unlisted methods default to Promise.resolve(undefined), never a
  // safe-empty shape.
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
  // Provider credentials live on an org-scoped API (TenantContext.useOrgApi) -- the Settings ->
  // Providers route calls it on mount, same "unlisted methods default to Promise.resolve(undefined)"
  // shape projectApi uses above.
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

// The active test case's projectApi/orgApi overrides — set by each it() before rendering, read by
// the `projectApi`/`orgApi` mock factories above (called lazily by TenantContext, after the module
// mock is already wired, so a module-level mutable slot is what connects the two).
// eslint-disable-next-line prefer-const
let currentProjectApiOverrides: Record<string, unknown> = {};
// eslint-disable-next-line prefer-const
let currentOrgApiOverrides: Record<string, unknown> = {};

// ---- console.error allowlist ------------------------------------------------------------------

// Populated by actually running the suite once and inventorying known-benign library/React noise
// that fires under jsdom + a MemoryRouter regardless of which view is mounted. Anything NOT on this
// list fails the test it fires in — see each route's settle loop below.
const CONSOLE_ERROR_ALLOWLIST: RegExp[] = [
  // React Router v7 warns about relative-splat resolution edge cases in dev builds; none of these
  // routes exercise the ambiguous case, but the warning is a `console.warn`-shaped message some
  // versions route through `console.error` under React's dev channel.
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

/** Every distinct `:paramName` token used across the manifest's fullPaths, mapped mechanically to
 * one fixed fake value per name (`fake-<name>`) — not hand-picked per route. */
const PARAM_NAMES = Array.from(
  new Set(VIEWS.flatMap((v) => Array.from(v.fullPath.matchAll(/:(\w+)/g)).map((m) => m[1]))),
);
const FIXED_PARAM_VALUES: Record<string, string> = Object.fromEntries(
  PARAM_NAMES.map((name) => [name, `fake-${name}`]),
);
// The two tenant params double as the fake org/project slugs used in the auth mock's fixtures
// above, so a view that cross-checks the URL's orgSlug/projectSlug against the fetched
// Organization/Project's own `slug` field still sees a consistent identity.
if (FIXED_PARAM_VALUES.orgSlug !== FAKE_ORG.slug || FIXED_PARAM_VALUES.projectSlug !== FAKE_PROJECT.slug) {
  throw new Error("routeManifest.smoke.test.tsx: FAKE_ORG/FAKE_PROJECT slugs must match FIXED_PARAM_VALUES");
}

function resolveUrl(fullPath: string): string {
  return fullPath.replace(/:(\w+)/g, (_, name: string) => FIXED_PARAM_VALUES[name]);
}

// ---- per-route projectApi overrides ------------------------------------------------------------

/**
 * The real answer a single-resource GET gives for one of this suite's `fake-<param>` ids: a 404
 * `ApiError`, which is exactly what the backend sends and what `http()` throws for it. Detail views
 * are reached here with ids that deliberately do not exist, so a 404 is the FAITHFUL response —
 * whereas the `Promise.resolve(undefined)` these overrides used before is a shape the endpoint can
 * never produce (a 200 carries real JSON, a genuine absence is `null`, a miss throws), and it only
 * ever exercised react-query's own "Query data cannot be undefined" dev-warning path. Rejecting
 * makes the route deterministically render its not-found/error state, which is a real render.
 */
const NOT_FOUND = (resource: string) => () =>
  Promise.reject(new ApiError(404, { code: `${resource}.not_found`, message: `no ${resource} with that id` }));

/** Shared across many entries below. A genuinely empty list is a valid real API response for
 * these endpoints, not a stand-in for "didn't bother" -- each is still an explicit named override. */
const EMPTY_TRIAGE = {
  cases: [],
  muted: [],
  recently_resolved: [],
  // Read directly (no `?.`) by Triage.tsx's all-clear state, which an empty `cases: []` triggers.
  // `traces_total`/`open_findings` are nullable on the wire and null only when the project has an
  // open queue; zero here is correct since this fixture has `cases: []`.
  watching: { classifiers: 0, call_sites: 0, traces_last_day: 0, traces_total: 0, open_findings: 0 },
};
const EMPTY = () => Promise.resolve([]);

/** Matches the full `Vitals` schema shape (dimension/groups/priced_models/total/window), not just
 * the two fields the pulse strip's happy path reads first -- an incomplete fixture here passed
 * only because the suite used to finish asserting before PulseStrip read `total.cost`. */
const EMPTY_GROUP = { cost: { usd: 0, delta_pct_per_turn: null, baseline_usd: null, calls: 0, unpriced_calls: 0, tokens: 0, flagged: false }, duration: { p50_ms: 0, p95_ms: 0, delta_pct: null, baseline_p95_ms: null, turns: 0, flagged: false }, flagged: false, key: null, label: null };
const EMPTY_VITALS = {
  dimension: "call_site",
  groups: [] as unknown[],
  priced_models: 0,
  total: EMPTY_GROUP,
  window: { from: "2026-01-01T00:00:00Z", to: "2026-01-08T00:00:00Z", days: 7, baseline_from: "2025-12-25T00:00:00Z", baseline_to: "2026-01-01T00:00:00Z" },
};

/** Matches the `OnboardingView` schema -- `useOnboarding` (Triage) reads `.stage` directly, no
 * `?.`, so an unmocked default trips the same "data cannot be undefined" failure on any route
 * that mounts it. */
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

/**
 * Matches `SubstrateStatusView` with `has_tagged_span: true` -- deliberately not the not-connected
 * shape a brand-new project would report. `ProjectShell` reads this on every project-scoped route
 * to decide whether to render `<ConnectGate/>` in place of the real view; without a connected
 * fixture here, every project-scoped route would mount the gate instead of the view under test.
 */
const CONNECTED_SUBSTRATE_STATUS = {
  has_live: true,
  untagged_spans: 0,
  has_tagged_span: true,
  spans_received: 1,
  tagged_spans: 1,
  last_span_at: "2026-01-01T00:00:00Z",
  service_name: "smoke-test",
};

/** Envelope-shaped list endpoints — each returns its own wrapper object, never a bare array, so an
 * `EMPTY` (`[]`) fixture would be the same wrong-but-plausible shape this suite exists to catch. */
const EMPTY_PROVIDER_CATALOG = { models: [] as unknown[], platforms: [] as unknown[] };
const EMPTY_PROVIDER_CREDENTIALS = { credentials: [] as unknown[] };
const EMPTY_MODEL_SETTINGS = { groups: [] as unknown[], lanes: [] as unknown[], models: [] as unknown[], settings: [] as unknown[], configured_providers: [] as unknown[] };
const EMPTY_REDACTION_RULES = { rules: [] as unknown[] };
const EMPTY_SESSION_SPANS = { spans: [] as unknown[], spans_truncated: false };

/**
 * Applied to every tenant-scoped route, view-specific overrides layered on top. `useCaseCounts`
 * calls `api.getTriage` for the nav badge from inside `ShellChrome`, which every tenant route
 * mounts regardless of which view it lands on -- an unmocked query resolving `undefined` logs a
 * "data cannot be undefined" console.error even when a `?.` consumer never lets it crash.
 *
 * `getVitals`/`onboarding` belong here too: `CapabilityGate` fail-closes while its capability read
 * is loading and redirects to Triage, so on every capability-gated route Triage briefly mounts,
 * starts these queries, and unmounts before they resolve -- React Query still runs them to
 * completion and logs the same warning.
 *
 * `substrateStatus` joins the set because `ProjectShell` reads it before `ShellChrome` (and every
 * route inside it) ever mounts -- see `CONNECTED_SUBSTRATE_STATUS`'s own comment.
 */
const SHELL_CHROME_OVERRIDES: Record<string, () => Promise<unknown>> = {
  getTriage: () => Promise.resolve(EMPTY_TRIAGE),
  getVitals: () => Promise.resolve(EMPTY_VITALS),
  onboarding: () => Promise.resolve(EMPTY_ONBOARDING),
  substrateStatus: () => Promise.resolve(CONNECTED_SUBSTRATE_STATUS),
  // Same transient-Triage-mount reason as getVitals/onboarding above: Triage reads
  // `configured_providers` to tell "found nothing" apart from "never ran", so this fires on
  // every tenant route, gated ones included.
  getModelSettings: () => Promise.resolve(EMPTY_MODEL_SETTINGS),
};

/** Per-view projectApi overrides, keyed by the manifest's raw (un-nested) `path` — unique enough
 * for this purpose since it only needs to key INTO the per-test override map, not to be a URL. */
const VIEW_OVERRIDES: Record<string, Record<string, () => Promise<unknown>>> = {
  "cases/:caseId": {
    getCase: NOT_FOUND("case"),
    // The header offers "Connect repository" beside Run RCA when the project has none, so the
    // case page now reads the integration too (useRepoPrompt).
    getGitIntegration: () => Promise.resolve(null),
  },
  sources: {
    listSources: EMPTY,
  },
  traces: {
    // Matches the real `TracesPage` schema (`traces`/`next_cursor`), not a generic
    // `items`/`next_cursor` shape -- `useObservedFacets` reads `pages.flatMap((p) => p.traces)`
    // with no `?.`.
    listTraces: () => Promise.resolve({ traces: [], next_cursor: null }),
  },
  "traces/:traceId": {
    getTrace: NOT_FOUND("trace"),
  },
  // SessionDetail issues BOTH reads on mount; the spans read is its own envelope
  // (`{spans, spans_truncated}`), not a bare array.
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
  // A groundedness finding, so the route renders a whole story (rate, pins, flagged answers with their
  // marks) rather than only its not-found state, which every other detail route already covers.
  "classifiers/findings/:findingId": {
    getBehaviorFinding: () => Promise.resolve(GROUNDEDNESS_FINDING_DETAIL),
  },
  vitals: {
    getVitals: () => Promise.resolve(EMPTY_VITALS),
  },
  // Legacy `/pipeline/*` URLs are the one entry here that still resolves to a VIEW rather than a
  // redirect: `ToProjectSegment segment="triage"` in App.tsx. The grader overview it used to land
  // on is gone, along with `getGraderStats`/`listObserverAlerts`/`listObserverProposals`, so what
  // remains is the triage surface's own queries. The `graders`, `review` and `observer` entries
  // that sat here are gone with their views -- those paths are redirects in the manifest now.
  "pipeline/*": {
    getPipeline: () => Promise.resolve(null),
    getTriage: () => Promise.resolve(EMPTY_TRIAGE),
  },
  "rca/:reportId": {
    getRcaReport: NOT_FOUND("rca report"),
    // Same read as the case page: the "analyzed without repository access" notice offers to fix it.
    getGitIntegration: () => Promise.resolve(null),
  },
  // ---- Settings sections -----------------------------------------------------------------------
  // Every one of these mounts a view that reads at least one project endpoint on mount, and each
  // needs the real shape its endpoint returns for an empty project.

  // `/settings` itself has no view: its index route redirects to `settings/sources`, so it lands on
  // Sources and issues Sources' read.
  settings: {
    listSources: EMPTY,
  },
  import: {
    listSources: EMPTY,
    getGitIntegration: () => Promise.resolve(null),
  },
  // listProviderCatalog/listProviderCredentials live on the org-scoped API -- see
  // VIEW_ORG_OVERRIDES below, not this (project-scoped) map.
  // The client coalesces the endpoint's empty body to `null` — a real "no repo connected" answer.
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
  // Keyed on the manifest's literal raw path, "*" collides two distinct routes: the project-level
  // catch-all (falls to Triage) and Settings' own catch-all (falls to Sources). `listSources` only
  // matters to the second but is harmless on the first, and there's no way to tell them apart by
  // path alone.
  "*": {
    listSources: EMPTY,
  },
};

// Provider credentials are org-scoped (TenantContext.useOrgApi), not project-scoped -- a second,
// smaller override map for the one route that reads them.
const VIEW_ORG_OVERRIDES: Record<string, Record<string, () => Promise<unknown>>> = {
  providers: {
    listProviderCatalog: () => Promise.resolve(EMPTY_PROVIDER_CATALOG),
    listProviderCredentials: () => Promise.resolve(EMPTY_PROVIDER_CREDENTIALS),
  },
};

// ---- real-data detail fixtures ----------------------------------------------------------------
// Shapes follow the generated schema types, so a field the API adds or renames fails `tsc` here.

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

/** One detail route mounted on a real payload: which manifest entry it is, the reads it answers,
 * and the main heading its success render must draw. */
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

// Every view is React.lazy-loaded (`named()` in App.tsx). In production that import gets a head
// start from AuthContext's `preloadRouteChunk`, keyed off `window.location.pathname` -- but this
// suite renders behind a MemoryRouter, which never touches `window.location`, so every test would
// otherwise hit a cold `import()` on first mount. That import can still be in flight at the
// moment the DOM looks non-blank and at the moment react-query first reports `isFetching() === 0`,
// so glob-importing every view module up front, once, removes the race at its source.
// Exclude *.test.tsx: it also matches sibling test files under views/, and eagerly importing one
// runs its top-level vi.mock() calls in this file's module context, silently overriding a real
// dependency for every test that runs after it.
const VIEW_MODULES = import.meta.glob(["./views/**/*.tsx", "!./views/**/*.test.tsx"]);

/**
 * Mounts the real App at `url` and waits until the routed view has genuinely settled (see the notes
 * inside). Throws if it never does. Returns the container for any content assertion.
 */
async function mountAndSettle(url: string): Promise<HTMLElement> {
  const { container, queryClient } = renderApp(url);

  await waitFor(
    () => {
      expect(container.textContent?.trim().length ?? 0).toBeGreaterThan(0);
    },
    { timeout: 5000 },
  );
  // Wait for the route to genuinely SETTLE — which means three things at once, none of which
  // the DOM merely looking non-blank implies:
  //
  //  1. The routed view has actually MOUNTED. Every view is React.lazy'd behind ProjectShell's
  //     own <Suspense> (App.tsx), and the shell chrome around that boundary already satisfies
  //     the non-blank check above — so "container has text" is true while the routed view is
  //     still nothing but a spinner. Waiting on the fallback's `aria-label="Loading view"` to
  //     disappear is what makes this a render smoke test of the VIEW rather than of the shell.
  //  2. react-query is idle, and
  //  3. the query cache's shape has been completely unchanged for a full QUIET_MS window —
  //     a single isFetching()===0 snapshot can be pure luck between two of one query's own
  //     state transitions, so it does not reliably outlast react-query's "Query data cannot be
  //     undefined" dev warning, which fires synchronously inside a query's fetch strictly
  //     before that query's status settles.
  //
  // Each poll re-enters `act()` DELIBERATELY. React flushes the work it has queued as an
  // `act()` scope EXITS, so a wait that runs entirely inside one long-lived act scope — which
  // is exactly what a single RTL `waitFor` is, since its asyncWrapper wraps the whole poll loop
  // in one act — parks React's lazy-import resolution in the act queue and never flushes it.
  // That was this file's real nondeterminism: on most project-scoped routes the view never
  // mounted at all before the assertion ran, so most of this suite was asserting against a
  // spinner, and the handful of runs where a view DID win the race were the ones that "flaked"
  // — its unmocked queries resolved undefined and tripped the console.error assertion. Short
  // act scopes in a loop flush per iteration, so every route now mounts its view every run.
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

  // Bug: a crash in the success render of the case, finding or RCA page (for example on a
  // secret-leak payload) ships with every test green, because the loop above only reaches them
  // with ids that 404.
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

  // The Sidebar project switcher's "Organizations" section (header, org rows, "+ New
  // organization") is not rendered in this test's mock -- it's the actual multi-org UI, not
  // NewOrg.tsx, and it renders on every authenticated page, so a wrong answer here would be far
  // more visible than on the one screen NewOrg used to live on.
  it("sidebar switcher has no Organizations section or New-organization action", async () => {
    currentProjectApiOverrides = { ...SHELL_CHROME_OVERRIDES };
    const url = resolveUrl("/orgs/:orgSlug/projects/:projectSlug/triage");
    const { container } = renderApp(url);

    // ProjectShell reads listProjects + substrateStatus before ever mounting ShellChrome, so a
    // bare "some text exists" check would pass on the transient "Loading…" screen (App.tsx's
    // `FullScreen`) rather than on the Sidebar this test actually asserts against. Wait for the
    // switcher button itself.
    await waitFor(() => {
      expect(container.querySelector('button[aria-haspopup="menu"]'), "project switcher button not found").toBeTruthy();
    });
    const switcherButton = container.querySelector('button[aria-haspopup="menu"]');
    // click, not mouseenter: ProjectSwitcher's onClick also opens the dropdown, and React 17+'s
    // enter/leave handling is computed off native mouseover/mouseout pairs, not a raw synthetic
    // "mouseenter" dispatch -- click is the reliable way to open it from a bare DOM event.
    (switcherButton as HTMLButtonElement).click();

    await waitFor(() => {
      // "Projects" -- the section that stays open -- must actually be there, so an absent
      // "Organizations" heading means no org section rendered, not that the dropdown itself
      // never opened.
      expect(container.textContent).toContain("Projects");
    });

    expect(container.textContent).not.toContain("Organizations");
    expect(container.textContent).not.toContain("New organization");
  });
});

/*
 * Where the app sends a visitor before any view mounts. The bugs worth catching: a signed-in user with
 * no organization left on a blank screen, an organization's only real project confused with its
 * sample, and a project with no tagged span yet shown the shell instead of the connect gate.
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
