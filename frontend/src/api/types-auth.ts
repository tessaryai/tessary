// SPDX-License-Identifier: Apache-2.0

/** Org-level RBAC roles. Mirrors the backend `Role` enum. */
export type OrgRole = "owner" | "admin" | "member" | "viewer" | "billing";

/**
 * `GET /auth/mode`: `redirectFlow` means the Login/Signup views bounce to `auth.loginUrl()` instead of
 * rendering a form. Read at runtime so a deployment can switch providers without a rebuild.
 */
export interface AuthMode {
  redirectFlow: boolean;
  /**
   * True while the deployment has no account, so `/login` hands the visitor to `/signup`. Always false
   * under a redirect-flow provider.
   */
  firstRun: boolean;
  /** A courtesy for the sign-up screen; enforcement is server-side. */
  signupPolicy: SignupPolicyMode;
}

/** Who may create an account on this install. Owner/admin-managed under Settings → Members. */
export type SignupPolicyMode = "open" | "domain" | "invite";

export interface SignupPolicy {
  mode: SignupPolicyMode;
  /** Email domains admitted without an invitation; only consulted in `domain` mode. */
  domains: string[];
  /** The policy lives on the install's first organization; a PUT through any other is refused. */
  governing: boolean;
  governing_org_slug: string | null;
  /** The governing organization's display name, what the UI shows; the slug is the URL segment. */
  governing_org_name: string | null;
}

/** Hand-written: springdoc cannot type `AuthController.establishSession`'s raw Map. */
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
  /** A rendering hint with no reader; every staff route re-checks server-side. */
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
   * One-way delete marker: keys are revoked, project routes 404 and a worker drains the data. Only the
   * org's project list still returns these, so settings can show the state.
   */
  deleting_at: string | null;
}

/**
 * Mirrors the backend's Project#isSample(). A malformed `settings` reads as false, the safe direction:
 * this only picks shell chrome and skips the project in implicit navigation.
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

/** `admin` grants tool access over /mcp and also satisfies write/query. */
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

type AuditAction = "created" | "rotated" | "revoked";

/** One audit-trail entry; newest-first, capped at 200 rows server-side. */
export interface ApiKeyAudit {
  id: string;
  project_id: string;
  api_key_id: string | null;
  actor_user_id: string | null;
  action: AuditAction;
  details: string | null;
  created_at: string;
}

// ---- Capabilities ----

/** Mirror of the backend Capability enum's `wire()` values. Keep in sync. */
export type CapabilityWire =
  // The graders, observer, review and agentic-synthesis keys are gone from the backend enum; a gate
  // reading one would fail closed on every org.
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

/** Every capability is present, so an absent key is version skew rather than "off". */
export interface CapabilitiesView {
  capabilities: Record<CapabilityWire, boolean>;
}
