// SPDX-License-Identifier: Apache-2.0
import { Navigate, Route, Routes, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense, type ComponentType, type ReactNode } from "react";
import { AuthProvider, useAuth } from "./auth/AuthContext";
import { ProtectedRoute } from "./auth/ProtectedRoute";
import { signOut } from "./auth/signOut";
import { TenantProvider, useProjectApi, useTenant } from "./tenant/TenantContext";
import { auth as authApi } from "./api/client";
import { isSampleProject } from "./api/types-auth";
import { ShellChrome } from "./shell";
import { projectsQuery, substrateStatusQuery } from "./shell/shellQueries";
import { CapabilityGate } from "./capabilities/CapabilityGate";
import { ConnectGate } from "./views/onboarding/ConnectGate";
import { Button, Spinner, ToastProvider } from "./ui";
import { registerRouteChunk, trackedChunk } from "./lib/routePreload";

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
  preloadAt?: string,
): M[K] extends ComponentType<infer P> ? ComponentType<P> : never {
  const chunk = trackedChunk(loader);
  if (preloadAt) registerRouteChunk(preloadAt, chunk.load);
  return lazy(() => {
    const module = chunk.loaded();
    if (module) return resolvedNow({ default: module[key] as ComponentType<unknown> });
    return chunk.load().then((m) => ({ default: m[key] as ComponentType<unknown> }));
  }) as never;
}

// Resolves inside `then`, so React.lazy renders an already-fetched chunk on the first pass. Suspending
// instead commits the fallback, and React then holds the view until 300 ms after it appeared.
function resolvedNow<T>(value: T): Promise<T> {
  return { then: (resolve: (v: T) => unknown) => resolve(value) } as unknown as Promise<T>;
}

const Login = named(() => import("./views/auth/Login"), "Login");
const Signup = named(() => import("./views/auth/Signup"), "Signup");
const Link = named(() => import("./views/Link"), "Link");
const NewProject = named(() => import("./views/NewProject"), "NewProject");

const Triage = named(() => import("./views/triage/Triage"), "Triage", "triage");
const CasePage = named(() => import("./views/triage/CasePage"), "CasePage", "cases");
const TracesIndex = named(() => import("./views/traces/TracesIndex"), "TracesIndex", "traces");
const TraceDetail = named(() => import("./views/traces/TraceDetail"), "TraceDetail");
const SessionDetail = named(() => import("./views/traces/SessionDetail"), "SessionDetail");
const ClassifiersPage = named(() => import("./views/classifiers/ClassifiersPage"), "ClassifiersPage", "classifiers");
const ClassifierConfigurePage = named(
  () => import("./views/classifiers/ClassifierConfigurePage"),
  "ClassifierConfigurePage",
);
const FindingPage = named(() => import("./views/classifiers/FindingPage"), "FindingPage");
const Vitals = named(() => import("./views/vitals/Vitals"), "Vitals", "vitals");
const PromptsPage = named(() => import("./views/prompts/PromptsPage"), "PromptsPage", "prompts");
const PromptDetailPage = named(() => import("./views/prompts/PromptDetailPage"), "PromptDetailPage");

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
const Features = named(() => import("./views/Settings/Features"), "Features");
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
            There is no pricing page. The redirect is load-bearing: this top-level Routes has no
            catch-all, so an unmatched /pricing would render a blank page rather than falling
            through anywhere.
          */}
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
  // `orgs` can be empty: a user who leaves their only org keeps the session until they sign in again.
  // That cannot go to `/login`, which sends a signed-in visitor straight back here.
  const { user } = useAuth();
  const first = user?.orgs[0];
  if (!first) return <NoOrganization />;
  return <Navigate to={`/orgs/${first.slug}`} replace />;
}

function NoOrganization() {
  return (
    <div className="h-screen bg-bg text-fg flex items-center justify-center p-8">
      <div className="max-w-md">
        <h1 className="text-h1 text-fg">No organization</h1>
        <p className="text-body text-muted mt-2">
          This account is not a member of any organization. Sign out and sign in again to get your own
          organization. To join someone else's, ask one of its admins for an invitation.
        </p>
        <Button className="mt-6" variant="secondary" onClick={() => void signOut()}>
          Sign out
        </Button>
      </div>
    </div>
  );
}

function OrgRedirect() {
  const { orgSlug } = useParams<{ orgSlug: string }>();
  const projects = useQuery({
    queryKey: ["projects", orgSlug],
    queryFn: () => authApi.listProjects(orgSlug!),
    enabled: !!orgSlug,
  });
  if (projects.isLoading) return <FullScreen>Loading…</FullScreen>;
  // The sample project is deletable demo data and must never be where a bare org URL lands.
  const first = projects.data?.filter((p) => !isSampleProject(p))[0];
  if (!first) return <Navigate to={`/orgs/${orgSlug}/new-project`} replace />;
  return <Navigate to={`/orgs/${orgSlug}/projects/${first.slug}/triage`} replace />;
}

/**
 * Decides the connect gate before ShellChrome renders, so the gate gets a sidebar-less full screen.
 * A gated project's first paint waits on both reads rather than flashing the shell. On a read error
 * it fails open rather than trap an onboarded org behind the gate.
 */
function ProjectShell() {
  const { orgSlug, projectSlug } = useTenant();
  const api = useProjectApi();

  const projects = useQuery(projectsQuery(orgSlug));
  const project = projects.data?.find((p) => p.slug === projectSlug);
  const isSample = project ? isSampleProject(project) : false;

  const substrate = useQuery({
    ...substrateStatusQuery(api),
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

        <Route path="triage" element={<Triage />} />
        <Route path="cases/:caseId" element={<CasePage />} />

        <Route path="traces" element={<TracesIndex />} />
        <Route path="traces/:traceId" element={<TraceDetail />} />
        <Route path="sessions/:sessionId" element={<SessionDetail />} />
        <Route path="classifiers" element={<ClassifiersPage />} />
        <Route path="classifiers/findings/:findingId" element={<FindingPage />} />
        {/* One classifier: switch, status, call sites, tuning, detections, reset. Static paths above outrank it. */}
        <Route path="classifiers/:classifierId" element={<ClassifierConfigurePage />} />
        {/* The Triage pulse strip's full-size page. Amber only, never red. */}
        <Route path="vitals" element={<Vitals />} />
        {/* The prompt id, not the name, so a name with a / never meets the router. */}
        <Route
          path="prompts"
          element={<CapabilityGate capability="prompt_management_enabled"><PromptsPage /></CapabilityGate>}
        />
        <Route
          path="prompts/:promptId"
          element={<CapabilityGate capability="prompt_management_enabled"><PromptDetailPage /></CapabilityGate>}
        />

        <Route path="rca/:reportId" element={<RcaReport />} />
        <Route path="concepts" element={<Concepts />} />

        {/* Gated sections carry the same CapabilityGate the sidebar reads, so a hand-typed URL lands on Triage. */}
        <Route path="settings" element={<SettingsLayout />}>
          <Route index element={<Navigate to="sources" replace />} />
          <Route path="sources" element={<Sources />} />
          <Route path="import" element={<ImportYaml />} />
          <Route
            path="providers"
            element={<CapabilityGate capability="byo_provider_keys_enabled"><Providers /></CapabilityGate>}
          />
          <Route path="git" element={<GitIntegration />} />
          <Route path="models" element={<Models />} />
          <Route
            path="notifications"
            element={<CapabilityGate capability="alerts_enabled"><Notifications /></CapabilityGate>}
          />
          <Route
            path="mcp-tokens"
            element={<CapabilityGate capability="api_access_enabled"><McpTokens /></CapabilityGate>}
          />
          <Route
            path="api-keys"
            element={<CapabilityGate capability="api_access_enabled"><ApiKeys /></CapabilityGate>}
          />
          {/* Ungated: the page states the built-in redaction defaults a security review reads. */}
          <Route path="pii-redaction" element={<PiiRedaction />} />
          <Route path="retention" element={<Retention />} />
          <Route path="members" element={<Members />} />
          <Route path="organization" element={<Organization />} />
          {/* Renamed from "workspace": redirect old bookmarks. */}
          <Route path="workspace" element={<Navigate to="../organization" replace />} />
          <Route path="features" element={<Features />} />
          {/* Absolute: a relative `to` resolves against the matched splat and loops. */}
          <Route path="*" element={<ToProjectSegment segment="settings/sources" />} />
        </Route>

        {/* Triage is never gated, so this cannot loop. */}
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
