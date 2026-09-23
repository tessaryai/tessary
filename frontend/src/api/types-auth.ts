// SPDX-License-Identifier: Apache-2.0
// Shapes returned by the new tenant-aware backend.
import type { components } from "./generated/schema";

type S = components["schemas"];

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
 * The shared body of `POST /auth/signup` and `POST /auth/login`, mirrors the backend's
 * `AuthController.SignupRequest`/`LoginRequest` records. springdoc infers these from `@RequestBody`
 * types today, but the response shape below is a raw `Map.of(...)` (see
 * `AuthController.establishSession`) that generation cannot see, so this file hand-authors both for
 * symmetry.
 */
export interface CredentialAuthRequest {
  email: string;
  password: string;
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

/**
 * A per-project environment (dev/staging/prod), the scoping dimension. Every project gets
 * dev/staging/prod on creation; data surfaces filter by the selected environment.
 */
export interface Environment {
  id: string;
  project_id: string;
  slug: string;
  name: string;
  /** Stable display ordering for the switcher (dev=0, staging=1, prod=2). */
  sort_order: number;
  /** The environment a write with no explicit env tag lands in (the seeded dev). */
  is_default: boolean;
  created_at: string;
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

/** Org-level observer schedule: when (and whether) accumulated pushes are graded. */
export interface ObserverSettingsView {
  enabled: boolean;
  /** The org's own cron, or null to use the server default cadence. */
  batch_cron: string | null;
  /** The cron actually used: the org's own, or the server default when unset. */
  effective_cron: string;
  last_batch_at: string | null;
}

export interface UpdateObserverSettingsRequest {
  enabled: boolean;
  batch_cron: string | null;
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
  | "behavior_drift_enabled"
  | "sop_conformance_enabled"
  | "triage_automatic_enabled";

/**
 * The org's capability object (`GET /api/orgs/{org}/capabilities`), the any-member read the whole
 * SPA is assembled from. Every capability is present with an explicit boolean, so an absent key is
 * a version skew rather than a meaningful "off".
 *
 * `unavailable` answers the second question separately: a capability can be off because nobody
 * turned it on, or absent because this build does not carry the code behind it. Those need
 * different UI, a switch versus an explanation, and the difference is not derivable from the map.
 */
export interface CapabilitiesView {
  capabilities: Record<CapabilityWire, boolean>;
  unavailable: CapabilityWire[];
}

/**
 * Whether LLM grading is running on this deployment (`GET /api/grading-status`).
 *
 * Deliberately carries no ceiling or spend figure: the breaker is deployment-wide, so those are the
 * operator's numbers and every tenant can read this endpoint. `since` is set only while `paused`.
 */
export interface GradingStatusView {
  paused: boolean;
  since: string | null;
}

/**
 * One billable unit's total metered consumption for an org over the period.
 * Field names are serialized verbatim from the backend `UsageLine` record (no renames); the
 * amount is `value`, matching `record UsageLine(String unit, long value)`.
 */
export type UsageLine = S["UsageLine"];

/**
 * One group of platform LLM usage: a lane, a project, a model, or the whole org. Serialized
 * verbatim from the backend `LlmUsageSliceView`.
 *
 * The four token buckets are carried apart because they are priced apart; `total_tokens` is their
 * sum. `cost_usd` covers only the calls the pricing catalog held a rate for, so a non-zero
 * `unpriced_calls` means the true cost is higher than the figure shown. `platform_cost_usd` and
 * `byo_cost_usd` split that same total by whose credential paid; never add them to `cost_usd`.
 */
export type LlmUsageSlice = S["LlmUsageSliceView"];

/**
 * The org's LLM token + cost breakdown over `[from, to)` (an open bound = the org's whole history
 * on that side), read live off the per-call ledger. `as_of` is genuinely now, unlike the bucketed
 * `BillingSummary.usage` totals.
 */
export type LlmUsage = S["LlmUsageView"];

/**
 * One `(bucket, series)` cell of the bucketed LLM usage read: the value of a single bar segment.
 * `bucket_start` joins to an entry of `LlmUsageSeries.buckets`; `key` identifies the series within
 * the requested grouping (empty string both for the ungrouped series and for calls that reported no
 * lane/model).
 */
export type LlmUsageCell = S["LlmUsageCellView"];

/**
 * The org's LLM usage over `[from, to)` bucketed at `grain` and cut into series by `grouping`, the
 * usage chart's feed. `buckets` is the complete x-axis including quiet buckets, while `cells` only
 * carries the buckets that had calls, so a gap stays a gap instead of compressing the time axis.
 * `total` is the same window under the same filters, so headline figures and bars always agree.
 */
export type LlmUsageSeries = S["LlmUsageSeriesView"];

/** Bucket widths the usage series supports. */
export type UsageGrain = "hour" | "day" | "week";

/** Series axes the usage series supports; `none` is the org total as one series. */
export type UsageGrouping = "none" | "lane" | "project" | "model";

/**
 * Cross-project billing rollup for an org (BILLING_MANAGE-gated). `plan`/`billing_email` are
 * permanently dead placeholder fields left behind after the charging integration was removed, and
 * `usage` is the real metered totals per unit. `billing_email` is omitted from the JSON when null
 * (ApiResponse is NON_NULL).
 */
export interface BillingSummary {
  org_id: string;
  plan: string;
  billing_email?: string;
  usage: UsageLine[];
}
