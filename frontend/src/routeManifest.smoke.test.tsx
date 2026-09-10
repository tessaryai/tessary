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
 * entries must NOT be wrapped in TenantProvider, which throws synchronously outside the tenant
 * prefix — see TenantContext.tsx). Mounting the real App and letting its own <Routes> tree pick
 * the wrapper avoids getting that call wrong here.
 *
 * MOCKING STRATEGY. auth.me()/getCapabilities()/getGradingStatus()/listProjects() are called
 * unconditionally on mount (AuthProvider, useCapabilities, the shell chrome's grading banner and
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
 * `projectApi(...)` is different, deliberately: each method a test case does not explicitly
 * override defaults to `Promise.resolve(undefined)` via a Proxy, not a safe-empty default. This
 * suite's whole purpose is catching a capability key or API shape that silently stopped resolving
 * — a safe-empty default (`[]`/`{}`) would let exactly that regression pass silently.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import App from "./App";
import { ThemeProvider } from "./ui/ThemeContext";
import { DensityProvider } from "./ui/density";
import { ApiError } from "./api/types";
import type { CapabilityWire } from "./api/types-auth";
import manifest from "./routeManifest.generated.json";

// ---- api/client mock -------------------------------------------------------------------------

// Capability defaults mirror CapabilityService.UNAVAILABLE_IN_OPEN_EDITION / OFF_BY_DEFAULT
// (backend/product/src/main/java/ai/tessary/plan/CapabilityService.java): every wire key is true
// except these five, which this deployment reports unavailable/off.
const UNAVAILABLE_OR_OFF_IN_OPEN_EDITION: CapabilityWire[] = [
  "triage_automatic_enabled",
  "behavior_drift_enabled",
  "sop_conformance_enabled",
  "frustration_enabled",
  "groundedness_enabled",
];
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
  "behavior_drift_enabled",
  "sop_conformance_enabled",
  "triage_automatic_enabled",
];
const OPEN_EDITION_CAPABILITIES: Record<CapabilityWire, boolean> = Object.fromEntries(
  ALL_CAPABILITY_KEYS.map((k) => [k, !UNAVAILABLE_OR_OFF_IN_OPEN_EDITION.includes(k)]),
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
    getCapabilities: vi.fn(() => Promise.resolve({ capabilities: OPEN_EDITION_CAPABILITIES, unavailable: UNAVAILABLE_OR_OFF_IN_OPEN_EDITION })),
    getGradingStatus: vi.fn(() => Promise.resolve({ paused: false, since: null })),
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
    createOrg: unusedMutation("createOrg"),
    addMember: unusedMutation("addMember"),
    revokeInvitation: unusedMutation("revokeInvitation"),
    updateMember: unusedMutation("updateMember"),
    removeMember: unusedMutation("removeMember"),
    createProject: unusedMutation("createProject"),
    getBilling: unusedMutation("getBilling"),
    getLlmUsage: unusedMutation("getLlmUsage"),
    getLlmUsageSeries: unusedMutation("getLlmUsageSeries"),
    updateOrg: unusedMutation("updateOrg"),
    // archiveOrg/unarchiveOrg/deleteOrg/transferOwnership live elsewhere now -- the `auth` export
    // here no longer has them to mock.
    updateProject: unusedMutation("updateProject"),
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
  "classifiers/findings/:findingId": {
    getBehaviorFinding: NOT_FOUND("behavior finding"),
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

// ---- rendering ----------------------------------------------------------------------------------

function renderApp(url: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } },
  });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <DensityProvider persist className="contents">
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
      // "Organizations" heading means the paid section didn't render, not that the dropdown itself
      // never opened.
      expect(container.textContent).toContain("Projects");
    });

    expect(container.textContent).not.toContain("Organizations");
    expect(container.textContent).not.toContain("New organization");
  });
});
