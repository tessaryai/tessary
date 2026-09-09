// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { auth } from "../../api/client";
import { paid } from "@paid";
import { ApiError } from "../../api/types";
import type { OrgMember } from "../../api/types-auth";
import { useAuth } from "../../auth/AuthContext";
import { useTenant } from "../../tenant/TenantContext";
import {
  Badge,
  Button,
  CopyButton,
  EmptyState,
  Field,
  Input,
  PageBody,
  PageHeader,
  Section,
  TBody,
  TD,
  TH,
  THead,
  TR,
  Table,
  useToast,
} from "../../ui";
import { AppearanceControls } from "./Appearance";

/**
 * Owner-facing organization administration: rename / archive / transfer-ownership /
 * delete the organization, plus per-project lifecycle (rename, set-default, archive,
 * delete). Mirrors the owner-gating the backend enforces: non-owners see a
 * read-only view. The "default project" invariant is surfaced here: the default
 * project cannot be archived or deleted, and exactly one project carries the badge.
 */
export function Organization() {
  const { orgSlug, projectSlug } = useTenant();
  const { user } = useAuth();
  const qc = useQueryClient();
  const toast = useToast();

  const org = useQuery({ queryKey: ["org", orgSlug], queryFn: () => auth.getOrg(orgSlug) });
  const projects = useQuery({
    queryKey: ["org-projects", orgSlug],
    queryFn: () => auth.listProjects(orgSlug),
    // A project being deleted is the one row on this page that changes without the user doing
    // anything: the purge worker removes it, and the row should go with it. Poll only while one is
    // actually in that state, so the page is otherwise as static as it was.
    refetchInterval: (query) => (query.state.data?.some((p) => p.deleting_at) ? 3000 : false),
  });
  const members = useQuery({ queryKey: ["org-members", orgSlug], queryFn: () => auth.listMembers(orgSlug) });

  const me = members.data?.find((m: OrgMember) => m.user_id === user?.id);
  const canManage = me?.role === "owner";

  const [name, setName] = useState("");
  useEffect(() => {
    if (org.data) setName(org.data.name);
  }, [org.data]);

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["org", orgSlug] });
    qc.invalidateQueries({ queryKey: ["org-projects", orgSlug] });
    qc.invalidateQueries({ queryKey: ["org-members", orgSlug] });
    // GET /auth/me carries the org list; AuthProvider owns its own refetch, invoked by
    // paid.orgLifecycleSections itself after a rename-shaped org change, not by this key.
  };

  const rename = useMutation({
    mutationFn: () => auth.updateOrg(orgSlug, { name: name.trim() }),
    onSuccess: () => {
      toast.success("Organization renamed");
      invalidate();
    },
    onError: (err) => toast.error("Could not rename organization", (err as ApiError).message),
  });

  // `paid.orgLifecycleSections` renders the archive/unarchive/delete-org and transfer-ownership
  // sections' UI and mutations; this page just hands it what it needs and how to invalidate its
  // own queries afterward.

  const setDefault = useMutation({
    mutationFn: (slug: string) => auth.makeProjectDefault(orgSlug, slug),
    onSuccess: () => {
      toast.success("Default project updated");
      invalidate();
    },
    onError: (err) => toast.error("Could not set default", (err as ApiError).message),
  });

  const toggleProjectArchive = useMutation({
    mutationFn: (p: { slug: string; archived: boolean }) =>
      p.archived ? auth.unarchiveProject(orgSlug, p.slug) : auth.archiveProject(orgSlug, p.slug),
    onSuccess: (_data, p) => {
      toast.success(p.archived ? "Project unarchived" : "Project archived");
      invalidate();
    },
    onError: (err, p) =>
      toast.error(p.archived ? "Could not unarchive project" : "Could not archive project", (err as ApiError).message),
  });

  // The endpoint returns 202, not 200: it revokes the project's API keys and marks it, then a worker
  // removes the data. So this reports what actually happened rather than claiming the project is gone.
  const deleteProject = useMutation({
    mutationFn: (slug: string) => auth.deleteProject(orgSlug, slug),
    onSuccess: () => {
      toast.success("Project deletion started", "Its API keys no longer work. The data is removed in the background.");
      invalidate();
    },
    onError: (err) => toast.error("Could not delete project", (err as ApiError).message),
  });

  const otherMembers = (members.data ?? []).filter((m) => m.user_id !== user?.id);

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Organization"
        title="Organization"
        subtitle={
          <>
            Rename, archive, transfer, or delete <span className="font-mono text-fg">{orgSlug}</span> and manage its
            projects. Owners only.
          </>
        }
      />

      <Section title="Appearance" subtitle="How Tessary looks for your account.">
        <AppearanceControls />
      </Section>

      {org.data?.archived_at && (
        <Section title="Archived">
          <p className="text-muted text-small">
            This organization is archived. It stays accessible but is hidden from active lists. Unarchive it below to
            restore it.
          </p>
        </Section>
      )}

      <Section title="Name">
        <div className="flex gap-2 items-end">
          <div className="flex-1">
            <Field label="Organization name">
              {(p) => (
                <Input
                  {...p}
                  value={name}
                  disabled={!canManage}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Acme Inc."
                />
              )}
            </Field>
          </div>
          <Button
            variant="primary"
            disabled={!canManage || !name.trim() || name.trim() === org.data?.name}
            loading={rename.isPending}
            onClick={() => rename.mutate()}
          >
            Rename
          </Button>
        </div>
      </Section>

      <Section title={`${projects.data?.length ?? 0} project${projects.data?.length === 1 ? "" : "s"}`}>
        {projects.data && projects.data.length === 0 ? (
          <EmptyState title="No projects yet" body="Every organization keeps at least one default project." />
        ) : (
          <Table>
            <THead>
              <TR>
                <TH>Project</TH>
                <TH>Status</TH>
                <TH></TH>
              </TR>
            </THead>
            <TBody>
              {projects.data?.map((p) => (
                <TR key={p.id}>
                  <TD>
                    <span className="font-mono">{p.slug}</span>
                    {p.is_default && (
                      <Badge tone="accent" className="ml-2">
                        default
                      </Badge>
                    )}
                    {p.slug === projectSlug && <span className="ml-1 text-subtle">(current)</span>}
                  </TD>
                  <TD>
                    {p.deleting_at ? (
                      <Badge tone="error">deleting…</Badge>
                    ) : p.archived_at ? (
                      <Badge tone="neutral">archived</Badge>
                    ) : (
                      <Badge tone="accent">active</Badge>
                    )}
                  </TD>
                  <TD className="text-right space-x-2">
                    {canManage && !p.is_default && !p.archived_at && !p.deleting_at && (
                      <Button size="sm" onClick={() => setDefault.mutate(p.slug)} disabled={setDefault.isPending}>
                        Make default
                      </Button>
                    )}
                    {canManage && !p.is_default && !p.deleting_at && (
                      <Button
                        size="sm"
                        onClick={() => toggleProjectArchive.mutate({ slug: p.slug, archived: !!p.archived_at })}
                        disabled={toggleProjectArchive.isPending}
                      >
                        {p.archived_at ? "Unarchive" : "Archive"}
                      </Button>
                    )}
                    {canManage && !p.is_default && !p.deleting_at && (
                      <Button
                        size="sm"
                        variant="danger"
                        onClick={() => {
                          if (
                            confirm(
                              `Delete project "${p.slug}"? This permanently deletes the project and its data. ` +
                                `Its API keys stop working immediately.`,
                            )
                          ) {
                            deleteProject.mutate(p.slug);
                          }
                        }}
                        disabled={deleteProject.isPending}
                      >
                        Delete project
                      </Button>
                    )}
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        )}
      </Section>

      {canManage &&
        paid.orgLifecycleSections({
          orgSlug,
          archived: !!org.data?.archived_at,
          otherMembers,
          onChanged: invalidate,
        })}

      <Section title="Organization ID" subtitle="Use this ID when contacting support or calling the API.">
        <div className="flex items-center gap-2.5 rounded-control border border-border bg-surface px-3.5 py-3 max-w-md">
          <span className="flex-1 min-w-0 font-mono text-small text-fg truncate">{org.data?.id}</span>
          <CopyButton
            value={() => org.data?.id}
            size="sm"
            disabled={!org.data}
            className="flex-none"
            onCopyFailed={() => toast.error("Could not copy", "Copy the organization ID manually.")}
          />
        </div>
      </Section>
    </PageBody>
  );
}
