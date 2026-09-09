// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Lock } from "lucide-react";
import { auth } from "../../api/client";
import { ApiError } from "../../api/types";
import { useAuth } from "../../auth/AuthContext";
import { useTenant } from "../../tenant/TenantContext";
import type { OrgRole, OrgMember, SignupPolicyMode } from "../../api/types-auth";
import {
  Badge,
  Button,
  Field,
  Input,
  Modal,
  PageBody,
  PageHeader,
  Select,
  Spinner,
  cn,
  useToast,
} from "../../ui";


const ROLE_LABELS: Record<OrgRole, string> = {
  owner: "Owner",
  admin: "Admin",
  member: "Member",
  viewer: "Viewer",
  billing: "Billing",
};

/** One-line capability summary per role, shown in the role-card row. */
const ROLE_DESCRIPTIONS: Record<OrgRole, string> = {
  owner: "Billing, deletion, and granting owner",
  admin: "Members and capabilities",
  member: "Cases, classifiers, projects, and keys",
  viewer: "Read-only access",
  billing: "Invoices and plan only",
};

/** Roles assignable from the UI, in descending privilege order. */
const ASSIGNABLE_ROLES: OrgRole[] = ["owner", "admin", "member", "viewer", "billing"];

/** Tiny inline lock. Mirrors the design's RBAC note. */
function LockGlyph() {
  return <Lock size={13} strokeWidth={1.7} aria-hidden="true" className="shrink-0" />;
}

function roleLabel(role: OrgRole): string {
  return ROLE_LABELS[role] ?? role;
}

function initial(m: OrgMember): string {
  const source = m.display_name?.trim() || m.email;
  return source.charAt(0).toUpperCase();
}

function joinedMonth(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: "short", year: "numeric" });
}

function invitedAgo(iso: string): string {
  const days = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 86_400_000));
  if (days === 0) return "invited today";
  if (days === 1) return "invited 1d ago";
  return `invited ${days}d ago`;
}

export function Members() {
  const { orgSlug } = useTenant();
  const { user } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();

  const members = useQuery({
    queryKey: ["org-members", orgSlug],
    queryFn: () => auth.listMembers(orgSlug),
  });

  const [inviteOpen, setInviteOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<OrgRole>("member");

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["org-members", orgSlug] });
    qc.invalidateQueries({ queryKey: ["org-invitations", orgSlug] });
  };

  const closeInvite = () => {
    setInviteOpen(false);
    setEmail("");
    setRole("member");
  };

  const add = useMutation({
    mutationFn: () => auth.addMember(orgSlug, { email: email.trim(), role }),
    onSuccess: (result) => {
      closeInvite();
      toast.success(result.status === "invited" ? "Invitation sent" : "Member added");
      invalidate();
    },
    onError: (err) => toast.error("Could not add member", (err as ApiError).message),
  });

  const updateRole = useMutation({
    mutationFn: (vars: { userId: string; role: OrgRole }) =>
      auth.updateMember(orgSlug, vars.userId, { role: vars.role }),
    onSuccess: () => {
      toast.success("Role updated");
      invalidate();
    },
    onError: (err) => toast.error("Could not change role", (err as ApiError).message),
  });

  const remove = useMutation({
    mutationFn: (userId: string) => auth.removeMember(orgSlug, userId),
    onSuccess: () => {
      toast.success("Member removed");
      invalidate();
    },
    onError: (err) => toast.error("Could not remove member", (err as ApiError).message),
  });

  const me = members.data?.find((m) => m.user_id === user?.id);
  // Mirrors the server MEMBERS_MANAGE permission (owner + admin). Affordance only —
  // the backend re-checks on every mutating call, so this is purely to hide controls.
  const canManage = me?.role === "owner" || me?.role === "admin";
  const isOwner = me?.role === "owner";

  const invitations = useQuery({
    queryKey: ["org-invitations", orgSlug],
    queryFn: () => auth.listInvitations(orgSlug),
    enabled: canManage,
  });

  const revokeInvite = useMutation({
    mutationFn: (invitationId: string) => auth.revokeInvitation(orgSlug, invitationId),
    onSuccess: () => {
      toast.success("Invitation revoked");
      qc.invalidateQueries({ queryKey: ["org-invitations", orgSlug] });
    },
    onError: (err) => toast.error("Could not revoke invitation", (err as ApiError).message),
  });

  const leaveOrg = () => {
    if (!me) return;
    if (confirm("Leave this organization? You will lose access to its projects.")) {
      remove.mutate(me.user_id);
    }
  };

  const roster = members.data ?? [];
  const pending = invitations.data ?? [];

  const policy = useQuery({
    queryKey: ["signup-policy", orgSlug],
    queryFn: () => auth.getSignupPolicy(orgSlug),
    enabled: canManage,
  });
  const [policyMode, setPolicyMode] = useState<SignupPolicyMode | null>(null);
  const [policyDomains, setPolicyDomains] = useState<string | null>(null);
  const shownMode = policyMode ?? policy.data?.mode ?? "open";
  const shownDomains = policyDomains ?? (policy.data?.domains ?? []).join(", ");
  const policyDirty =
    policy.data !== undefined &&
    (shownMode !== policy.data.mode ||
      (shownMode === "domain" && shownDomains !== policy.data.domains.join(", ")));

  const savePolicy = useMutation({
    mutationFn: () =>
      auth.updateSignupPolicy(orgSlug, {
        mode: shownMode,
        domains:
          shownMode === "domain"
            ? shownDomains.split(/[\s,]+/).map((d) => d.trim()).filter(Boolean)
            : [],
      }),
    onSuccess: () => {
      toast.success("Sign-up policy saved");
      setPolicyMode(null);
      setPolicyDomains(null);
      qc.invalidateQueries({ queryKey: ["signup-policy", orgSlug] });
      qc.invalidateQueries({ queryKey: ["auth-mode"] });
    },
    onError: (err) => toast.error("Could not save the sign-up policy", (err as ApiError).message),
  });

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Organization"
        title="Members"
        subtitle={
          <>
            Who can access <span className="font-mono text-fg">{orgSlug}</span>, and what each person can do.
          </>
        }
        actions={
          canManage ? (
            <Button variant="primary" onClick={() => setInviteOpen(true)}>
              Invite
            </Button>
          ) : (
            <Button variant="secondary" loading={remove.isPending} onClick={leaveOrg}>
              Leave organization
            </Button>
          )
        }
      />

      {/* Role legend — the five RBAC roles and what each can do. */}
      <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-3 lg:grid-cols-5">
        {ASSIGNABLE_ROLES.map((rl) => (
          <div key={rl} className="rounded-card bg-surface px-2.5 py-2.5">
            <div className="text-small font-medium text-fg">{roleLabel(rl)}</div>
            <div className="mt-0.5 text-small text-muted leading-snug">
              {ROLE_DESCRIPTIONS[rl]}
            </div>
          </div>
        ))}
      </div>

      {/* Roster */}
      {members.isLoading ? (
        <div className="flex items-center gap-2 py-10 text-small text-muted">
          <Spinner size="sm" /> Loading members…
        </div>
      ) : roster.length === 0 ? (
        <div className="py-12 text-center">
          <p className="text-body text-fg">No members yet</p>
          <p className="text-small text-muted mt-1">
            {canManage
              ? "Invite a teammate to share this organization."
              : "Members did not load. Reload the page to try again."}
          </p>
          {canManage && (
            <Button variant="primary" className="mt-4" onClick={() => setInviteOpen(true)}>
              Invite
            </Button>
          )}
        </div>
      ) : (
        <div className="rounded-card border border-border overflow-hidden">
          <div className="flex items-center gap-3 px-4 py-2.5 border-b border-border bg-surface text-column-header text-muted">
            <span className="flex-1">Member</span>
            <span className="w-24">Joined</span>
            <span className="w-36">Role</span>
          </div>
          {roster.map((m, i) => {
            const isYou = m.user_id === user?.id;
            // Admins can manage non-owners; only an owner can edit/remove another owner.
            const targetIsOwner = m.role === "owner";
            const canEditRow = canManage && !isYou && (isOwner || !targetIsOwner);
            const canRemoveRow = canManage && !isYou && (isOwner || !targetIsOwner);
            return (
              <div
                key={m.user_id}
                className={cn(
                  "flex items-center gap-3 px-4 py-3",
                  i < roster.length - 1 && "border-b border-border",
                )}
              >
                <span className="size-7 rounded-pill bg-raised flex items-center justify-center text-label text-fg flex-none">
                  {initial(m)}
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block text-small text-fg truncate">
                    {m.display_name ?? m.email}
                    {isYou && <span className="text-subtle"> · you</span>}
                  </span>
                  <span className="block text-label text-muted truncate">{m.email}</span>
                </span>
                <span className="w-24 text-label text-muted">{joinedMonth(m.created_at)}</span>
                <span className="w-36">
                  {canEditRow ? (
                    <Select
                      value={m.role}
                      disabled={updateRole.isPending}
                      onChange={(e) => {
                        const next = e.target.value as OrgRole;
                        if (next === m.role) return;
                        updateRole.mutate({ userId: m.user_id, role: next });
                      }}
                      className="h-7 text-small focus:border-border-strong">
                      {ASSIGNABLE_ROLES.map((rl) => (
                        // Only an owner can grant the owner role.
                        <option key={rl} value={rl} disabled={rl === "owner" && !isOwner}>
                          {roleLabel(rl)}
                        </option>
                      ))}
                    </Select>
                  ) : isYou ? (
                    <Badge tone={m.role === "owner" ? "accent" : "neutral"}>
                      {roleLabel(m.role)} · you
                    </Badge>
                  ) : (
                    <span className="text-label text-muted">{roleLabel(m.role)}</span>
                  )}
                </span>
                {canRemoveRow && (
                  <button
                    type="button"
                    onClick={() => {
                      if (confirm(`Remove ${m.email} from this organization? They lose access to its projects.`)) {
                        remove.mutate(m.user_id);
                      }
                    }}
                    className="text-label text-muted hover:text-error transition-colors flex-none">
                    Remove
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* RBAC lock note — only an owner can grant the owner role; the selectors disable it otherwise. */}
      <div className="flex items-center gap-2 rounded-card bg-raised px-3 py-2 text-muted">
        <LockGlyph />
        <span className="text-small text-muted">
          Only an <span className="text-fg">owner</span> can grant the owner role. The option is disabled
          for everyone else.
        </span>
      </div>

      {/* Sign-up policy (owner + admin): who may create an account on this install. */}
      {canManage && (
        <div className="mt-6">
          <div className="flex items-baseline gap-2.5">
            <span className="text-label uppercase text-muted">Sign-up policy</span>
          </div>
          <p className="text-small text-muted mt-1">
            Who can create an account on this instance. Creating an account gives someone their own organization;
            invite them from this page to join this one, and an invitation also admits its address in every mode.
            Existing members are never locked out by a change here.
          </p>
          {policy.data && !policy.data.governing && (
            <p className="text-small text-muted mt-2" role="note">
              The policy is instance-wide and is managed under organization{" "}
              <span className="font-mono text-fg">{policy.data.governing_org_slug ?? "unknown"}</span>; it is shown
              here read-only.
            </p>
          )}
          <div className="mt-3 rounded-card border border-border divide-y divide-border">
            {(
              [
                ["open", "Anyone", "Anyone who can reach the instance can create an account of their own."],
                ["domain", "Listed email domains", "Only addresses at the domains below can create an account; anyone invited still can."],
                ["invite", "Invitation only", "Nobody gets an account without a pending invitation."],
              ] as [SignupPolicyMode, string, string][]
            ).map(([value, label, hint]) => (
              <label key={value} className="flex items-start gap-3 px-4 py-3 cursor-pointer">
                <input
                  type="radio"
                  name="signup-policy"
                  value={value}
                  checked={shownMode === value}
                  disabled={policy.isLoading || savePolicy.isPending || policy.data?.governing === false}
                  onChange={() => setPolicyMode(value)}
                  className="mt-1"
                />
                <span className="min-w-0">
                  <span className="block text-small text-fg">{label}</span>
                  <span className="block text-label text-muted">{hint}</span>
                </span>
              </label>
            ))}
            {shownMode === "domain" && (
              <div className="px-4 py-3">
                <Field label="Email domains" hint="Comma-separated, for example acme.com, acme.io">
                  {(p) => (
                    <Input
                      {...p}
                      value={shownDomains}
                      onChange={(e) => setPolicyDomains(e.target.value)}
                      placeholder="acme.com, acme.io"
                      disabled={savePolicy.isPending || policy.data?.governing === false}
                    />
                  )}
                </Field>
              </div>
            )}
          </div>
          <div className="mt-3 flex justify-end">
            <Button
              variant="primary"
              disabled={!policyDirty || policy.data?.governing === false}
              loading={savePolicy.isPending}
              onClick={() => savePolicy.mutate()}
            >
              Save policy
            </Button>
          </div>
        </div>
      )}

      {/* Pending invitations (owner only) */}
      {canManage && pending.length > 0 && (
        <div className="mt-6">
          <div className="flex items-baseline gap-2.5">
            <span className="text-label uppercase text-muted">
              Pending invitations
            </span>
            <span className="text-label text-subtle">{pending.length}</span>
          </div>
          <div className="mt-2.5 flex flex-col gap-2.5">
            {pending.map((inv) => (
              <div
                key={inv.id}
                className="flex items-center gap-3 rounded-card border border-dashed border-border-strong px-4 py-3">
                <span className="size-7 rounded-pill border border-dashed border-border-strong flex items-center justify-center text-label text-muted flex-none">
                  ?
                </span>
                <span className="flex-1 min-w-0 text-small text-muted truncate">{inv.email}</span>
                <span className="text-label text-subtle whitespace-nowrap">
                  {invitedAgo(inv.created_at)} · {roleLabel(inv.role)}
                </span>
                <button
                  type="button"
                  disabled={revokeInvite.isPending}
                  onClick={() => {
                    if (confirm(`Revoke the invitation for ${inv.email}? They can no longer accept it.`)) {
                      revokeInvite.mutate(inv.id);
                    }
                  }}
                  className="text-label text-muted hover:text-error transition-colors flex-none disabled:opacity-50">
                  Revoke
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Invite modal (owner only) */}
      <Modal
        open={inviteOpen}
        onClose={closeInvite}
        title="Invite a member"
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={closeInvite}>
              Cancel
            </Button>
            <Button
              variant="primary"
              disabled={!email.trim()}
              loading={add.isPending}
              onClick={() => add.mutate()}
            >
              Send invitation
            </Button>
          </>
        }
      >
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (email.trim()) add.mutate();
          }}
        >
          <Field label="Email">
            {(p) => (
              <Input
                {...p}
                type="email"
                autoFocus
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@example.com"
              />
            )}
          </Field>
          <Field
            label="Role"
            hint="Owners control everything. Admins also manage members and capabilities. Members work cases, tune classifiers, and manage projects and keys. Viewers read only. Billing sees billing only."
          >
            {(p) => (
              <Select {...p} value={role} onChange={(e) => setRole(e.target.value as OrgRole)}>
                {ASSIGNABLE_ROLES.map((rl) => (
                  // Only an owner can invite another owner.
                  <option key={rl} value={rl} disabled={rl === "owner" && !isOwner}>
                    {roleLabel(rl)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </form>
      </Modal>
    </PageBody>
  );
}
