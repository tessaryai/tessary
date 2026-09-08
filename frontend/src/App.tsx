// SPDX-License-Identifier: Apache-2.0
import { Navigate, Route, Routes, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense, type ComponentType, type ReactNode } from "react";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { TenantProvider, useProjectApi, useTenant } from "./tenant/TenantContext";
import { auth as authApi } from "./api/client";
import { isSampleProject } from "./api/types-auth";
import { ShellChrome } from "./shell";
import { CapabilityGate } from "./capabilities/CapabilityGate";
import { ConnectGate } from "./views/onboarding/ConnectGate";
import { Spinner, ToastProvider } from "./ui";
import { registerRouteChunk } from "./lib/routePreload";
// The paid seam (open-core D2). In this tree `@paid` is `src/paid/index.ts`, whose route
// arrays are empty; a commercial build aliases it to the overlay's registry. See that file.
import { paid } from "@paid";

/*
 * Route-level code-splitting. Every view is lazy-loaded so the initial
 * bundle carries only the auth/shell/router skeleton; each surface's JS is
 * fetched the first time it is visited. This is the performance win that clears
 * Vite's >500 kB chunk-size advisory — see vite.config.ts for the companion
 * vendor-chunk split. `named` adapts our named exports to the default export
 * React.lazy expects, so view files keep their named exports unchanged.
 */
function named<M, K extends keyof M>(
  loader: () => Promise<M>,
  key: K,
  /**
   * The first path segment under `/orgs/:org/projects/:project/` this view is mounted at. Supplying
   * it registers the chunk for {@link preloadRouteChunk} — see that function for why.
   */
  preloadAt?: string,
): M[K] extends ComponentType<infer P> ? ComponentType<P> : never {
  if (preloadAt) registerRouteChunk(preloadAt, loader);
  // React.lazy expects a module with a `default` export; our views use named
  // exports, so adapt here while preserving each component's prop types.
  return lazy(async () => ({ default: (await loader())[key] as ComponentType<unknown> })) as never;
}

const Login = named(() => import("./views/auth/Login"), "Login");
const Signup = named(() => import("./views/auth/Signup"), "Signup");
const Link = named(() => import("./views/Link"), "Link");
const NewProject = named(() => import("./views/NewProject"), "NewProject");

// 2026-07 redesign IA — Triage · Monitor (Traces, Classifiers, Vitals) ·
// Monitor (Traces, Classifiers, Vitals). Each view is owned by its surface
// agent; the sheet named in each stub's header is the spec.
const Triage = named(() => import("./views/triage/Triage"), "Triage", "triage");
const CasePage = named(() => import("./views/triage/CasePage"), "CasePage", "cases");
const TracesIndex = named(() => import("./views/traces/TracesIndex"), "TracesIndex", "traces");
const TraceDetail = named(() => import("./views/traces/TraceDetail"), "TraceDetail");
const SessionDetail = named(() => import("./views/traces/SessionDetail"), "SessionDetail");
const ClassifiersPage = named(() => import("./views/classifiers/ClassifiersPage"), "ClassifiersPage", "classifiers");
const DetectorsPage = named(() => import("./views/classifiers/DetectorsPage"), "DetectorsPage");
const FindingPage = named(() => import("./views/classifiers/FindingPage"), "FindingPage");
const Vitals = named(() => import("./views/vitals/Vitals"), "Vitals", "vitals");

// Surviving pre-redesign surfaces (route-only or referenced from flows).
const Sources = named(() => import("./views/Sources"), "Sources");
const Concepts = named(() => import("./views/Concepts"), "Concepts");
const RcaReport = named(() => import("./views/RcaReport"), "RcaReport");
const SettingsLayout = named(() => import("./views/Settings/SettingsLayout"), "SettingsLayout", "settings");
const ImportYaml = named(() => import("./views/Settings/Import"), "ImportYaml");
const McpTokens = named(() => import("./views/Settings/McpTokens"), "McpTokens");
const ApiKeys = named(() => import("./views/Settings/ApiKeys"), "ApiKeys");
const PiiRedaction = named(() => import("./views/Settings/PiiRedaction"), "PiiRedaction");
const Retention = named(() => import("./views/Settings/Retention"), "Retention");
const Notifications = named(() => import("./views/Settings/Notifications"), "Notifications");
const Members = named(() => import("./views/Settings/Members"), "Members");
const Providers = named(() => import("./views/Settings/Providers"), "Providers");
const GitIntegration = named(() => import("./views/Settings/GitIntegration"), "GitIntegration");
const Models = named(() => import("./views/Settings/Models"), "Models");
const Organization = named(() => import("./views/Settings/Organization"), "Organization");

export default function App() {
  return (
    <AuthProvider>
      <ToastProvider>
        <Suspense fallback={<FullScreen>Loading…</FullScreen>}>
        <Routes>
          {/*
            Pricing is PAID and excluded from this build (#846), not gated: it is the one public
            route — outside ProtectedRoute and outside TenantProvider — and `CapabilityGate` calls
            `useTenant()`, which throws with no provider above it, so gating it would be a
            render-time crash on a public URL. The paid registry supplies the real route here.

            The redirect below it is load-bearing: this top-level <Routes> has NO catch-all, so an
            unmatched /pricing would render a BLANK page rather than falling through anywhere. It
            sits AFTER the spread deliberately — react-router ranks by specificity and both are
            static, so in the paid build the registry's own /pricing wins and this never matches.
          */}
          {paid.publicRoutes}
          <Route path="/pricing" element={<Navigate to="/" replace />} />
          {/*
            /login and /signup (#853) join /pricing in the same public, unauthenticated tier --
            outside ProtectedRoute and outside TenantProvider. This is where the backend's own
            GET /auth/login degrade branch now bounces an unauthenticated visitor, breaking the
            redirect loop that existed when that branch bounced to the app root instead (which sits
            behind ProtectedRoute, which sends an unauthenticated visitor right back to that same
            GET).
          */}
          <Route path="/login" element={<Login />} />
          <Route path="/signup" element={<Signup />} />
          <Route path="/link" element={<ProtectedRoute><Link /></ProtectedRoute>} />
          {/*
            /new-org (#862): creating an additional org past the open build's single-org cap is a
            paid-only action. The route is mounted inside ProtectedRoute (needs a signed-in user)
            but outside TenantProvider (no org selected yet), same tier as the redirects around it
            -- the paid registry supplies the real route here, and the open build simply has none.
          */}
          {paid.protectedRoutes}
          <Route path="/" element={<ProtectedRoute><RootRedirect /></ProtectedRoute>} />
          <Route path="/orgs/:orgSlug" element={<ProtectedRoute><OrgRedirect /></ProtectedRoute>} />
          <Route path="/orgs/:orgSlug/new-project" element={<ProtectedRoute><NewProject /></ProtectedRoute>} />
          <Route
            path="/orgs/:orgSlug/projects/:projectSlug/*"
            element={
              <ProtectedRoute>
                <TenantProvider>
                  <ProjectShell />
                </TenantProvider>
              </ProtectedRoute>
            }
          />
        </Routes>
        </Suspense>
      </ToastProvider>
    </AuthProvider>
  );
}

function RootRedirect() {
  // #862: GET /api/me/orgs moved to the paid overlay -- read the org list off GET /auth/me instead
  // (already fetched: this renders inside ProtectedRoute, which blocks on AuthProvider's own
  // fetch, so there is no loading state left for this component to own).
  //
  // #1227: the org-creation wizard (Onboarding.tsx) is gone. TenantService#ensureDefaultOrg runs on
  // every signup and login, so an authenticated user always has >=1 org by the time this renders --
  // `orgs` is empty only in a state that cannot happen post-auth, not a real fork to design a redirect
  // for. `/login` is the safe, already-public landing for that theoretical case: a cosmetic choice,
  // not a real branch, since this component only ever renders inside ProtectedRoute.
  const { user } = useAuth();
  const first = user?.orgs[0];
  if (!first) return <Navigate to="/login" replace />;
  return <Navigate to={`/orgs/${first.slug}`} replace />;
}

function OrgRedirect() {
  const { orgSlug } = useParams<{ orgSlug: string }>();
  const projects = useQuery({
    queryKey: ["projects", orgSlug],
    queryFn: () => authApi.listProjects(orgSlug!),
    enabled: !!orgSlug,
  });
  if (projects.isLoading) return <FullScreen>Loading…</FullScreen>;
  // The sample project (#1227) is filtered out of implicit "which project" resolution here for the
  // same reason TenantService.ensureDefaultProject filters it on the backend: it is a lazily-created,
  // deletable demo row that must never become where a bare /orgs/:orgSlug navigation lands, even if
  // its created_at somehow sorts before the org's real project.
  const first = projects.data?.filter((p) => !isSampleProject(p))[0];
  if (!first) return <Navigate to={`/orgs/${orgSlug}/new-project`} replace />;
  return <Navigate to={`/orgs/${orgSlug}/projects/${first.slug}/triage`} replace />;
}

/*
 * The shell fetches nothing. It is chrome and a router outlet.
 *
 * It used to own two queries and render a spinner in place of the routes until both resolved.
 * Because the route subtree never mounted, React never began fetching the route's lazy chunk and
 * the page never issued its own queries — so on every surface in the app a 674 kB payload sat in
 * front of the chunk download AND the page's data. Measured on production: the page's own request
 * did not start until 62-93% of the load had elapsed, and the route chunk alone (993 ms) began only
 * after /pipeline returned.
 *
 * Both queries are gone rather than merely unblocked:
 *
 *   - `/pipeline` had one job left here, the first-run poll that advanced the old Setup wizard, so the
 *     query moved out. Every other pipeline consumer always issued its own.
 *   - `/curation` had NO reader at all. `getCuration` was called here and nowhere else, and no
 *     component read the result from the cache; the surfaces that once used it went with an earlier
 *     redesign. It was a request per page load whose response was discarded.
 */
/**
 * Decides whether this project is behind the connect gate (#1227) before rendering ShellChrome or
 * any route inside it — the only way to give the gate the sidebar-less, nav-less full screen the
 * design calls for, since ShellChrome always renders `<Sidebar/>`.
 *
 * Two independent reads gate the decision, both cheap and both already-established signals rather
 * than new plumbing invented for this: `listProjects` (the same query OrgRedirect and Sidebar's
 * project switcher already run, keyed identically so react-query shares the cache) tells us whether
 * THIS project is the sample project, and `substrateStatus` (extended additively for #1227, see
 * SubstrateController) tells us whether a tagged span has ever landed. Neither query blocks
 * ShellChrome from mounting once it resolves in the open direction -- but the first paint for a
 * genuinely gated project necessarily waits on both, which is the point: a shell that briefly shows
 * before the gate slams shut is worse than a short, one-time loading beat here.
 *
 * On a read ERROR (either query) this fails OPEN -- renders the normal shell -- rather than trapping
 * an already-onboarded org behind a gate a backend hiccup made unreadable.
 */
function ProjectShell() {
  const { orgSlug, projectSlug } = useTenant();
  const api = useProjectApi();

  const projects = useQuery({
    queryKey: ["projects", orgSlug],
    queryFn: () => authApi.listProjects(orgSlug),
  });
  const project = projects.data?.find((p) => p.slug === projectSlug);
  const isSample = project ? isSampleProject(project) : false;

  const substrate = useQuery({
    queryKey: ["substrate-status", api.base],
    queryFn: api.substrateStatus,
    // Only worth polling here while the gate might still be open -- once a project is known-sample
    // or the shell has already been chosen this render, ConnectGate (if mounted) owns its own poll.
    enabled: !isSample,
    refetchInterval: (q) => (q.state.data?.has_tagged_span ? false : 3500),
  });

  const stillDeciding = projects.isLoading || (!isSample && substrate.isLoading);
  const gated = !isSample && !substrate.isError && substrate.data?.has_tagged_span === false;

  if (stillDeciding) {
    return <FullScreen>Loading…</FullScreen>;
  }

  if (gated) {
    return <ConnectGate />;
  }

  return (
    <ShellChrome isSample={isSample}>
      <Suspense
        fallback={
          <div className="h-full flex items-center justify-center" role="status" aria-label="Loading view">
            <Spinner size="lg" />
          </div>
        }
      >
      <Routes>
        <Route index element={<Navigate to="triage" replace />} />

        {/* ── Triage — the front door: an inbox that wants to be empty. ── */}
        <Route path="triage" element={<Triage />} />
        {/* One case = one degradation. The summoned path's landing. */}
        <Route path="cases/:caseId" element={<CasePage />} />

        {/* ── Monitor ── */}
        <Route path="traces" element={<TracesIndex />} />
        <Route path="traces/:traceId" element={<TraceDetail />} />
        <Route path="sessions/:sessionId" element={<SessionDetail />} />
        {/* Classifiers absorbs the old Behavior drift page (detectors + findings). */}
        <Route path="classifiers" element={<ClassifiersPage />} />
        {/* The catalog and the tuning, off the queue rather than above it. */}
        <Route path="classifiers/detectors" element={<DetectorsPage />} />
        {/* A finding's own evidence, which the queue links every row to. */}
        <Route path="classifiers/findings/:findingId" element={<FindingPage />} />
        {/* The Triage pulse strip's full-size page. Amber only, never red. */}
        <Route path="vitals" element={<Vitals />} />

        {/* ── Surviving pre-redesign routes (no sidebar entry). ── */}
        {/* One RCA report — deep-linked from cases. */}
        <Route path="rca/:reportId" element={<RcaReport />} />
        <Route path="concepts" element={<Concepts />} />

        {/* Settings — administration only; concept tuning lives with the concept.
            Gated sections carry the same CapabilityGate the sidebar filter reads, so a bookmark or a
            hand-typed URL lands on Triage rather than on a page the org has no business seeing. */}
        <Route path="settings" element={<SettingsLayout />}>
          <Route index element={<Navigate to="sources" replace />} />
          <Route path="sources" element={<Sources />} />
          {/* Ungated since Track A: bundle import was behind `graders_enabled`, and that capability is
              gone. Importing a pipeline is plain authoring, like the pipeline write surfaces. */}
          <Route path="import" element={<ImportYaml />} />
          <Route
            path="providers"
            element={<CapabilityGate capability="byo_provider_keys_enabled"><Providers /></CapabilityGate>}
          />
          <Route path="git" element={<GitIntegration />} />
          <Route path="models" element={<Models />} />
          {/* The observer surface it pointed at went with Track A; fold to Sources like any other
              retired settings tab rather than redirecting to a route that no longer exists. */}
          <Route path="observer" element={<Navigate to="../sources" replace />} />
          {/* The summoned flow's admin surface: Slack channel, cadence, quiet hours. */}
          <Route path="notifications" element={<Notifications />} />
          {/* Signal tuning moved into the Classifiers detail rails (one home per concept). */}
          <Route path="signal-tuning" element={<Navigate to="../../classifiers" replace />} />
          <Route
            path="mcp-tokens"
            element={<CapabilityGate capability="api_access_enabled"><McpTokens /></CapabilityGate>}
          />
          <Route
            path="api-keys"
            element={<CapabilityGate capability="api_access_enabled"><ApiKeys /></CapabilityGate>}
          />
          {/* Ungated on purpose: the page also states the built-in redaction defaults, which is what a
              security review reads. `custom_redaction_enabled` gates authoring inside it. */}
          <Route path="pii-redaction" element={<PiiRedaction />} />
          <Route path="retention" element={<Retention />} />
          <Route path="members" element={<Members />} />
          {/* Usage is PAID (#846) — the metered-units and LLM-spend screen. Excluded from this
              build rather than gated: no Capability names usage (the screen gates on the
              BILLING_MANAGE role instead), and adding one is #837's territory. The settings
              catch-all below folds `settings/usage` to Sources here, so no blank page and no
              redirect of its own is needed; `shell/nav.tsx` drops the rail/palette entry with it. */}
          {paid.settingsRoutes}
          <Route path="organization" element={<Organization />} />
          {/* Renamed from "workspace" — redirect old bookmarks. */}
          <Route path="workspace" element={<Navigate to="../organization" replace />} />
          {/* Appearance lives under Organization; feature flags are managed via
              LaunchDarkly, not in-app. Redirect old bookmarks. */}
          <Route path="appearance" element={<Navigate to="../organization" replace />} />
          <Route path="feature-flags" element={<Navigate to="../sources" replace />} />
          {/* Any other settings path — a retired tab, a typo, a deep link into a section that was
              removed — folds to Sources rather than rendering an empty <Outlet/> (segment F3).
              Absolute, like the project-level catch-all: a relative `to="sources"` resolves against
              the splat it matched, so /settings/typo redirected to /settings/typo/sources, matched
              this route again, and appended `sources` until react-router gave up. */}
          <Route path="*" element={<ToProjectSegment segment="settings/sources" />} />
        </Route>

        {/* ── Old IA → new IA (2026-07 migration map). ── */}
        <Route path="overview" element={<Navigate to="../triage" replace />} />
        <Route path="explore" element={<Navigate to="../traces" replace />} />
        <Route path="pipeline" element={<Navigate to="../triage" replace />} />
        {/* Deep pipeline bookmarks (call-site editors, drilldowns) fold to the index. */}
        <Route path="pipeline/*" element={<ToProjectSegment segment="triage" />} />
        <Route path="behavior-drift" element={<Navigate to="../classifiers" replace />} />
        {/* Repo sync and the observer it renamed both went with Track A. Kept as redirects rather
            than deleted: a bookmark to either has to land somewhere, and Triage is never gated. */}
        <Route path="repo-sync" element={<Navigate to="../triage" replace />} />
        <Route path="observer" element={<Navigate to="../triage" replace />} />
        <Route path="graders" element={<Navigate to="../triage" replace />} />
        <Route path="graders/:graderId" element={<Navigate to="../triage" replace />} />
        <Route path="review" element={<Navigate to="../triage" replace />} />
        <Route path="review/:queueId" element={<Navigate to="../triage" replace />} />
        <Route path="runs/batch/:runId" element={<Navigate to="../triage" replace />} />
        <Route path="datasets/:datasetId" element={<Navigate to="../triage" replace />} />
        <Route path="datasets/new" element={<Navigate to="../triage" replace />} />
        {/* Escalations live inside cases now; batch runs keep their detail route above. */}
        <Route path="runs" element={<Navigate to="../triage" replace />} />
        <Route path="runs/:runId" element={<Navigate to="../triage" replace />} />

        {/* Older bookmark URLs — redirect into the current IA. */}
        <Route path="signals" element={<Navigate to="../classifiers" replace />} />
        <Route path="failure-modes" element={<Navigate to="../triage" replace />} />
        <Route path="issues" element={<Navigate to="../triage" replace />} />
        <Route path="intents" element={<Navigate to="../triage" replace />} />
        <Route path="results" element={<Navigate to="../triage" replace />} />
        <Route path="entries/:entryId" element={<Navigate to="../traces" replace />} />
        <Route path="call-sites" element={<Navigate to="../triage" replace />} />
        <Route path="chains" element={<Navigate to="../triage" replace />} />
        <Route path="invariants" element={<Navigate to="../triage" replace />} />
        <Route path="packs" element={<Navigate to="../triage" replace />} />
        <Route path="taxonomy" element={<Navigate to="../triage" replace />} />
        <Route path="judge" element={<Navigate to="../triage" replace />} />
        {/* Datasets went with Track A; Library never had a page (DESIGN-DIRECTION §7). */}
        <Route path="datasets" element={<Navigate to="../triage" replace />} />
        <Route path="library" element={<Navigate to="../triage" replace />} />
        <Route path="sources" element={<Navigate to="../settings/sources" replace />} />

        {/* The catch-all. Every unmatched project path lands on Triage instead of rendering a blank
            shell — a removed route, a mistyped segment and a bookmark from a retired IA all resolve
            somewhere (segment F3). Triage is never gated, so this cannot loop. */}
        <Route path="*" element={<ToProjectSegment segment="triage" />} />
      </Routes>
      </Suspense>
    </ShellChrome>
  );
}

/** Absolute tenant-scoped redirect — for splat routes where relative `..` is ambiguous. */
function ToProjectSegment({ segment }: { segment: string }) {
  const { orgSlug, projectSlug } = useParams<{ orgSlug: string; projectSlug: string }>();
  return <Navigate to={`/orgs/${orgSlug}/projects/${projectSlug}/${segment}`} replace />;
}

/*
 * `ShellError` lived here and rendered "Failed to load pipeline" / "Pipeline parse failed" in place
 * of the entire app. Both branches are gone with the blocking gate:
 *
 *   - the parse-failure branch was already unreachable — PipelineController hardcodes `ok: true`
 *     because the schema is DB-validated and there is no parse step left to fail;
 *   - the fetch-failure branch was reachable, but blanking every surface because ONE payload failed
 *     is the coupling this change exists to remove. Traces, Classifiers, Vitals and Settings never
 *     read the pipeline; a 500 on it should not take them down.
 */

function FullScreen({ children }: { children: ReactNode }) {
  return (
    <div className="h-screen bg-bg text-fg flex items-center justify-center p-8">
      <div className="max-w-2xl text-muted text-small flex items-center gap-3">
        <Spinner />
        {children}
      </div>
    </div>
  );
}
