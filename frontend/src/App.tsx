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
// Route arrays this build registers through the `@paid` alias; see src/paid/index.ts.
import { paid } from "@paid";

/*
 * Route-level code-splitting. Every view is lazy-loaded so the initial
 * bundle carries only the auth/shell/router skeleton; each surface's JS is
 * fetched the first time it is visited. This is the performance win that clears
 * Vite's >500 kB chunk-size advisory, see vite.config.ts for the companion
 * vendor-chunk split. `named` adapts our named exports to the default export
 * React.lazy expects, so view files keep their named exports unchanged.
 */
function named<M, K extends keyof M>(
  loader: () => Promise<M>,
  key: K,
  /**
   * The first path segment under `/orgs/:org/projects/:project/` this view is mounted at. Supplying
   * it registers the chunk for {@link preloadRouteChunk}, see that function for why.
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

// 2026-07 redesign IA: Triage, Monitor (Traces, Classifiers, Vitals). Each view is owned by its
// surface agent; the sheet named in each stub's header is the spec.
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
            Pricing is excluded from this build, not gated: it is the one public route (outside
            ProtectedRoute and outside TenantProvider), and CapabilityGate calls useTenant(), which
            throws with no provider above it, so gating it would be a render-time crash on a public
            URL. paid.publicRoutes supplies a route here when one is registered.

            The redirect below it is load-bearing: this top-level Routes has no catch-all, so an
            unmatched /pricing would render a blank page rather than falling through anywhere. It
            sits after the spread deliberately: react-router ranks by specificity and both are
            static, so when paid.publicRoutes supplies /pricing, that route wins and this one never
            matches.
          */}
          {paid.publicRoutes}
          <Route path="/pricing" element={<Navigate to="/" replace />} />
          {/*
            /login and /signup join /pricing in the same public, unauthenticated tier: outside
            ProtectedRoute and outside TenantProvider. This is where the backend's GET /auth/login
            degrade branch bounces an unauthenticated visitor; bouncing to the app root instead
            would loop, since that route sits behind ProtectedRoute, which sends an unauthenticated
            visitor right back to that same GET.
          */}
          <Route path="/login" element={<Login />} />
          <Route path="/signup" element={<Signup />} />
          <Route path="/link" element={<ProtectedRoute><Link /></ProtectedRoute>} />
          {/*
            /new-org: creating an additional org past this build's single-org cap needs a route
            this build does not register on its own. Mounted inside ProtectedRoute (needs a
            signed-in user) but outside TenantProvider (no org selected yet), same tier as the
            redirects around it. paid.protectedRoutes supplies it when one is registered.
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
  // The org list comes off GET /auth/me (already fetched: this renders inside ProtectedRoute,
  // which blocks on AuthProvider's own fetch, so there is no loading state left for this
  // component to own).
  //
  // TenantService#ensureDefaultOrg runs on every signup and login, so an authenticated user
  // always has >=1 org by the time this renders: `orgs` is empty only in a state that cannot
  // happen post-auth, not a real fork to design a redirect for. `/login` is the safe,
  // already-public landing for that theoretical case: a cosmetic choice, not a real branch,
  // since this component only ever renders inside ProtectedRoute.
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
  // The sample project is filtered out of implicit "which project" resolution here for the same
  // reason TenantService.ensureDefaultProject filters it on the backend: it is a lazily-created,
  // deletable demo row that must never become where a bare /orgs/:orgSlug navigation lands, even
  // if its created_at somehow sorts before the org's real project.
  const first = projects.data?.filter((p) => !isSampleProject(p))[0];
  if (!first) return <Navigate to={`/orgs/${orgSlug}/new-project`} replace />;
  return <Navigate to={`/orgs/${orgSlug}/projects/${first.slug}/triage`} replace />;
}

/*
 * The shell fetches nothing. It is chrome and a router outlet, so a route's lazy chunk and its
 * own data queries start as soon as the route subtree mounts, with nothing blocking ahead of them.
 */
/**
 * Decides whether this project is behind the connect gate before rendering ShellChrome or any
 * route inside it: the only way to give the gate the sidebar-less, nav-less full screen the design
 * calls for, since ShellChrome always renders `<Sidebar/>`.
 *
 * Two independent reads gate the decision, both cheap and both already-established signals rather
 * than new plumbing: `listProjects` (the same query OrgRedirect and Sidebar's project switcher
 * already run, keyed identically so react-query shares the cache) tells us whether this project is
 * the sample project, and `substrateStatus` tells us whether a tagged span has ever landed. Neither
 * query blocks ShellChrome from mounting once it resolves in the open direction, but the first
 * paint for a genuinely gated project necessarily waits on both, which is the point: a shell that
 * briefly shows before the gate slams shut is worse than a short, one-time loading beat here.
 *
 * On a read error (either query) this fails open, rendering the normal shell, rather than trapping
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

        {/* ── Triage, the front door: an inbox that wants to be empty. ── */}
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
        {/* One RCA report, deep-linked from cases. */}
        <Route path="rca/:reportId" element={<RcaReport />} />
        <Route path="concepts" element={<Concepts />} />

        {/* Settings: administration only; concept tuning lives with the concept.
            Gated sections carry the same CapabilityGate the sidebar filter reads, so a bookmark or a
            hand-typed URL lands on Triage rather than on a page the org has no business seeing. */}
        <Route path="settings" element={<SettingsLayout />}>
          <Route index element={<Navigate to="sources" replace />} />
          <Route path="sources" element={<Sources />} />
          {/* Ungated: bundle import was behind `graders_enabled`, and that capability is gone.
              Importing a pipeline is plain authoring, like the pipeline write surfaces. */}
          <Route path="import" element={<ImportYaml />} />
          <Route
            path="providers"
            element={<CapabilityGate capability="byo_provider_keys_enabled"><Providers /></CapabilityGate>}
          />
          <Route path="git" element={<GitIntegration />} />
          <Route path="models" element={<Models />} />
          {/* The observer surface it pointed at is gone; fold to Sources like any other retired
              settings tab rather than redirecting to a route that no longer exists. */}
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
          {/* Usage (the metered-units and LLM-spend screen) is excluded from this build rather
              than gated: no Capability names it, since it gates on the BILLING_MANAGE role
              instead. The settings catch-all below folds `settings/usage` to Sources here, so no
              blank page and no redirect of its own is needed; `shell/nav.tsx` drops the
              rail/palette entry with it. */}
          {paid.settingsRoutes}
          <Route path="organization" element={<Organization />} />
          {/* Renamed from "workspace": redirect old bookmarks. */}
          <Route path="workspace" element={<Navigate to="../organization" replace />} />
          {/* Appearance lives under Organization; feature flags are managed via
              LaunchDarkly, not in-app. Redirect old bookmarks. */}
          <Route path="appearance" element={<Navigate to="../organization" replace />} />
          <Route path="feature-flags" element={<Navigate to="../sources" replace />} />
          {/* Any other settings path: a retired tab, a typo, a deep link into a section that was
              removed, folds to Sources rather than rendering an empty <Outlet/>. Absolute, like the
              project-level catch-all: a relative `to="sources"` resolves against the splat it
              matched, so /settings/typo redirected to /settings/typo/sources, matched this route
              again, and appended `sources` until react-router gave up. */}
          <Route path="*" element={<ToProjectSegment segment="settings/sources" />} />
        </Route>

        {/* ── Old IA → new IA (2026-07 migration map). ── */}
        <Route path="overview" element={<Navigate to="../triage" replace />} />
        <Route path="explore" element={<Navigate to="../traces" replace />} />
        <Route path="pipeline" element={<Navigate to="../triage" replace />} />
        {/* Deep pipeline bookmarks (call-site editors, drilldowns) fold to the index. */}
        <Route path="pipeline/*" element={<ToProjectSegment segment="triage" />} />
        <Route path="behavior-drift" element={<Navigate to="../classifiers" replace />} />
        {/* Repo sync and the observer it renamed are both gone. Kept as redirects rather than
            deleted: a bookmark to either has to land somewhere, and Triage is never gated. */}
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

        {/* Older bookmark URLs: redirect into the current IA. */}
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
        {/* Datasets is gone; Library never had a page. */}
        <Route path="datasets" element={<Navigate to="../triage" replace />} />
        <Route path="library" element={<Navigate to="../triage" replace />} />
        <Route path="sources" element={<Navigate to="../settings/sources" replace />} />

        {/* The catch-all. Every unmatched project path lands on Triage instead of rendering a blank
            shell: a removed route, a mistyped segment and a bookmark from a retired IA all resolve
            somewhere. Triage is never gated, so this cannot loop. */}
        <Route path="*" element={<ToProjectSegment segment="triage" />} />
      </Routes>
      </Suspense>
    </ShellChrome>
  );
}

/** Absolute tenant-scoped redirect, for splat routes where relative `..` is ambiguous. */
function ToProjectSegment({ segment }: { segment: string }) {
  const { orgSlug, projectSlug } = useParams<{ orgSlug: string; projectSlug: string }>();
  return <Navigate to={`/orgs/${orgSlug}/projects/${projectSlug}/${segment}`} replace />;
}

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
