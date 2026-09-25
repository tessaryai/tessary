// SPDX-License-Identifier: Apache-2.0
// Shapes returned by the new tenant-aware backend.

/** Org-level RBAC roles. Mirrors the backend `Role` enum. */
export type OrgRole = "owner" | "admin" | "member" | "viewer" | "billing";

/**
 * `GET /auth/mode`: which flow the active {@code AuthProvider} drives. `redirectFlow=true` means
 * WorkOS (or any future redirect-based provider) is active and the Login/Signup views must bounce
 * to `auth.loginUrl()` instead of rendering a form; `false` means the dependency-free
 * email/password provider is active and the form is the real UI. Polled on mount rather than baked
 * into the client bundle so a deployment can switch providers (e.g. a self-hoster adding WorkOS
 * credentials) without a rebuild.
 */
export interface AuthMode {
  redirectFlow: boolean;
  /**
   * True when this deployment has no account yet, so `/login` hands the visitor straight to
   * `/signup` rather than showing a sign-in form nothing can satisfy. Always false under a
   * redirect-flow provider, which owns its own signup screen. Goes false for good the moment the
   * first account exists.
   */
  firstRun: boolean;
  /**
   * The sign-up policy in force: `open`, `domain`, or `invite`. A courtesy for the sign-up screen,
   * so it can say what the server will refuse before the visitor fills in a form; the enforcement
   * is server-side regardless.
   */
  signupPolicy: SignupPolicyMode;
}

/** Who may create an account on this install. Owner/admin-managed under Settings → Members. */
export type SignupPolicyMode = "open" | "domain" | "invite";

export interface SignupPolicy {
  mode: SignupPolicyMode;
  /** Email domains admitted without an invitation; only consulted in `domain` mode. */
  domains: string[];
  /**
   * The policy is instance-wide and lives on the install's first organization. True when the
   * organization this was read through is that one; a PUT through any other is refused.
   */
  governing: boolean;
  governing_org_slug: string | null;
}

/**
 * The 200 body of `POST /auth/signup` and `POST /auth/login`, mirrors
 * `AuthController.establishSession`'s `Map.of("id", ..., "email", ..., "orgId", ...)`. Not
 * generated: springdoc can't infer a type from a raw `Map` response.
 */
export interface CredentialAuthResult {
  id: string;
  email: string;
  orgId: string;
}

export interface MeOrg {
  id: string;
  slug: string;
  name: string;
  role: OrgRole;
}

export interface Me {
  id: string;
  email: string;
  orgs: MeOrg[];
  /**
   * Whether this user is on the deployment's platform-staff allowlist. A rendering hint only, with
   * no reader today: it stays on the wire because the staff routes it hints at are still live
   * (`/api/admin/orgs/{orgSlug}/plan`), and because every staff route re-checks identity and
   * owner/admin standing in the target org server-side, so nothing is gated on this value.
   */
  platform_staff: boolean;
}

export interface Organization {
  id: string;
  workos_org_id: string | null;
  slug: string;
  name: string;
  created_at: string;
  /** Soft-archive marker (ISO instant); non-null means the organization is archived but resolvable. */
  archived_at: string | null;
  /** Per-organization JSON settings blob (owner-managed, extensible). */
  settings: string | null;
}

export interface Project {
  id: string;
  org_id: string;
  slug: string;
  name: string;
  description: string | null;
  created_at: string;
  /** Soft-archive marker (ISO instant); non-null means the project is archived. */
  archived_at: string | null;
  /** Per-project JSON settings blob (owner-managed, extensible). */
  settings: string | null;
  /** The one guaranteed default project per organization. */
  is_default: boolean;
  /**
   * One-way delete marker (ISO instant). Non-null means DELETE was accepted: the project's API keys
   * are already revoked, every project-scoped route now 404s, and a background worker is removing its
   * data. Unlike `archived_at` this never clears: the row's next state is gone. Only the org's
   * project list still returns these, so settings can show the state instead of the row vanishing
   * before its data has actually drained.
   */
  deleting_at: string | null;
}

/**
 * Mirrors the backend's {@code Project#isSample()}: true for the quiet, lazily-created sample
 * project a "Start with a sample project" click mints, marked by {@code {"sample": true}} in the
 * project's own `settings` blob rather than a dedicated column. A malformed or absent `settings`
 * reads as false, the same false-negative-is-safe direction the backend takes: this flag only picks
 * which shell chrome renders and which project implicit "first project" navigation skips, never
 * anything that should 500 on a parse failure.
 */
export function isSampleProject(project: Pick<Project, "settings">): boolean {
  if (!project.settings) return false;
  try {
    return JSON.parse(project.settings)?.sample === true;
  } catch {
    return false;
  }
}

export interface OrgMember {
  user_id: string;
  email: string;
  display_name: string | null;
  avatar_url: string | null;
  role: OrgRole;
  created_at: string;
}

export interface OrgInvitation {
  id: string;
  email: string;
  role: OrgRole;
  state: "pending" | "accepted" | "revoked";
  created_at: string;
}

export interface AddMemberResult {
  status: "added" | "invited";
  member: OrgMember | null;
  invitation: OrgInvitation | null;
}

export interface McpTokenView {
  id: string;
  name: string;
  token_prefix: string;
  created_at: string;
  last_used_at: string | null;
  revoked_at: string | null;
  created_by_user_id: string;
}

export interface IssueTokenResponse {
  token: McpTokenView;
  plaintext: string;
  warning: string;
}

// ---- Managed API keys: project-scoped, ORG_MANAGE to mint ----
// Distinct from the member-mintable MCP personal tokens: managed keys add scope
// (write/query/admin), per-environment scoping, rotation, and an audit trail.

/**
 * What an API key may do, the backend's wire vocabulary (`KeyScope`): `write` ingests traces,
 * `query` reads the query API, `admin` grants tool access over /mcp (and is the superset that
 * also satisfies write/query).
 */
export type KeyScope = "write" | "query" | "admin";

/** Non-secret view of a managed key. Omits the token hash and project id by design. */
export interface ApiKey {
  id: string;
  name: string;
  scope: KeyScope;
  /** Display-only prefix (e.g. "tsy_w_…"); safe to show. */
  token_prefix: string;
  created_at: string;
  last_used_at: string | null;
  /** null/blank = active; non-null = revoked. */
  revoked_at: string | null;
  created_by_user_id: string;
}

/** Create request body. Name and scope are both required. */
export interface CreateKeyRequest {
  name: string;
  scope: KeyScope;
}

/** Returned by create and rotate (HTTP 201): the only place the plaintext appears. */
export interface IssuedKeyResponse {
  key: ApiKey;
  /** The plaintext secret, returned only here, never stored. */
  plaintext: string;
  warning: string;
}

export interface RevokeKeyResponse {
  revoked: boolean;
}

/** Audit-log action wire values (lowercase of CREATED|ROTATED|REVOKED). */
export type AuditAction = "created" | "rotated" | "revoked";

/** One audit-trail entry; newest-first, capped at 200 rows server-side. */
export interface ApiKeyAudit {
  id: string;
  project_id: string;
  /** The key the action targeted. */
  api_key_id: string | null;
  /** Who performed it. */
  actor_user_id: string | null;
  action: AuditAction;
  details: string | null;
  created_at: string;
}

// ---- Capabilities ----
// Stable snake_case wire values from the backend `Capability` enum. This build has one tier and
// uncapped ingest, so there is no tier to name and no cap to report.

/**
 * Every capability key, the mirror of the backend `ai.tessary.plan.Capability` enum's `wire()`
 * values, which are also the LaunchDarkly flag keys. Keep in sync with that enum.
 *
 * The browser has no LaunchDarkly client and no plan logic: the backend resolves all of this per
 * session and the SPA only reads the answer.
 */
export type CapabilityWire =
  // "graders_enabled", "observer_enabled", "human_review_enabled" and "agentic_synthesis_enabled"
  // were here until grading, the observer and the review queues were removed. Do not reintroduce
  // one of those keys: the backend enum no longer defines it, so a gate reading it would fail closed
  // on every org.
  | "ci_integration_enabled"
  | "rca_enabled"
  | "api_access_enabled"
  | "alerts_enabled"
  | "slack_enabled"
  | "custom_redaction_enabled"
  | "byo_provider_keys_enabled"
  | "duration_drift_enabled"
  | "cost_drift_enabled"
  | "tool_error_enabled"
  | "frustration_enabled"
  | "groundedness_enabled"
  | "secret_leak_enabled"
  | "malformed_output_enabled"
  | "triage_automatic_enabled";

/**
 * The org's capability object (`GET /api/orgs/{org}/capabilities`), the any-member read the whole
 * SPA is assembled from. Every capability is present with an explicit boolean, so an absent key is
 * a version skew rather than a meaningful "off".
 */
export interface CapabilitiesView {
  capabilities: Record<CapabilityWire, boolean>;
}
