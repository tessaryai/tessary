// SPDX-License-Identifier: Apache-2.0
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { auth, link, projectApi } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ApiError } from "../api/types";
import { Button, Field, Input, Select, Spinner } from "../ui";

export function Link() {
  const [params] = useSearchParams();
  const code = params.get("code") ?? "";
  const nav = useNavigate();

  const linkQuery = useQuery({
    queryKey: ["device-link", code],
    queryFn: () => link.get(code),
    enabled: code.length > 0,
    retry: false,
  });

  // #862: GET /api/me/orgs moved to the paid overlay -- the org picker below reads the list off
  // GET /auth/me (already fetched by AuthProvider) instead of a second query. The open build's
  // account has exactly one org, so this Select renders one option; a paid account's full list
  // still comes through the same field once it carries more than one.
  const { user } = useAuth();
  const orgs = user?.orgs ?? [];
  const [orgSlug, setOrgSlug] = useState("");
  useEffect(() => {
    if (!orgSlug && orgs.length > 0) setOrgSlug(orgs[0].slug);
  }, [orgs, orgSlug]);

  const projects = useQuery({
    queryKey: ["projects", orgSlug],
    queryFn: () => auth.listProjects(orgSlug),
    enabled: orgSlug.length > 0,
  });
  const [projectSlug, setProjectSlug] = useState("");
  useEffect(() => {
    if (projects.data && projects.data.length > 0) {
      setProjectSlug((cur) => (cur && projects.data!.some((p) => p.slug === cur) ? cur : projects.data![0].slug));
    } else {
      setProjectSlug("");
    }
  }, [projects.data]);

  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState("");
  const createProject = useMutation({
    mutationFn: () => auth.createProject(orgSlug, { name: newName.trim() }),
    onSuccess: async (p) => {
      await projects.refetch();
      setProjectSlug(p.slug);
      setCreating(false);
      setNewName("");
    },
  });

  const confirm = useMutation({
    mutationFn: () => link.confirm(code, { org_slug: orgSlug, project_slug: projectSlug }),
  });
  const deny = useMutation({ mutationFn: () => link.deny(code) });

  // Once *this* browser confirms the link, hold on the "Connected" screen and poll
  // the project's pipeline until the plugin publishes its graders — then route in,
  // so the project lands already warm rather than flashing an empty state while it
  // catches up. Same cache key as the shell (`["pipeline", base]`), so the pipeline
  // is warm on arrival and the step is correct immediately. We only do this for our
  // own confirm, not a pre-claimed link (its selected project may be a default, not
  // the linked one). The terminal keeps polling independently.
  const linkedApi = useMemo(
    () => (confirm.isSuccess && orgSlug && projectSlug ? projectApi(orgSlug, projectSlug) : null),
    [confirm.isSuccess, orgSlug, projectSlug],
  );
  const pipelinePoll = useQuery({
    queryKey: ["pipeline", linkedApi?.base ?? "unlinked"],
    queryFn: () => linkedApi!.getPipeline(),
    enabled: !!linkedApi,
    refetchInterval: (q) => {
      const env = q.state.data;
      const ready = !!env?.ok && env.pipeline.call_sites.length > 0;
      return ready ? false : 2000;
    },
  });
  const env = pipelinePoll.data;
  const pipelineReady = !!env?.ok && env.pipeline.call_sites.length > 0;

  useEffect(() => {
    if (!confirm.isSuccess || !projectSlug) return;
    const go = () => nav(`/orgs/${orgSlug}/projects/${projectSlug}/overview`);
    if (pipelineReady) {
      go();
      return;
    }
    // Safety net: if a publish stalls or fails, don't trap the user on the spinner —
    // fall through to the wizard, whose own step-1 listening state takes over.
    const fallback = setTimeout(go, 45_000);
    return () => clearTimeout(fallback);
  }, [confirm.isSuccess, pipelineReady, orgSlug, projectSlug, nav]);

  if (!code) {
    return <Centered title="Invalid link" body="This connect link is missing its code. Re-run the command in your terminal." />;
  }
  if (linkQuery.isLoading) {
    return (
      <div className="min-h-screen bg-bg text-fg flex items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }
  if (linkQuery.isError) {
    return <Centered title="Link not found" body="This connect link doesn't exist. Re-run the command in your terminal to get a fresh one." />;
  }
  const status = linkQuery.data!.status;
  if (status === "expired") {
    return <Centered title="Link expired" body="This connect link has expired. Re-run the command in your terminal to get a fresh one." />;
  }
  if (confirm.isSuccess) {
    // This browser just confirmed: graders are publishing and we're routing into the
    // project (see the auto-nav effect). Pure transition — a spinner, no button.
    return (
      <Centered
        title="Connected"
        body="Your session is linked. Opening your project…">
        <div className="mt-6 flex justify-center">
          <Spinner size="lg" />
        </div>
      </Centered>
    );
  }
  if (status === "claimed") {
    // Link was already claimed elsewhere — we can't be sure which project, so we
    // don't auto-redirect; offer a manual way in.
    return (
      <Centered
        title="Connected"
        body="Your Claude Code session is linked. Return to your terminal. It continues on its own.">
        {projectSlug && (
          <Button
            variant="primary"
            className="mt-2"
            onClick={() => nav(`/orgs/${orgSlug}/projects/${projectSlug}/triage`)}
          >
            Open the project
          </Button>
        )}
      </Centered>
    );
  }
  if (status === "denied" || deny.isSuccess) {
    return <Centered title="Link declined" body="This connect request was declined. You can close this tab." />;
  }

  const label = linkQuery.data!.clientLabel ?? "A Claude Code session";
  const confirmError = confirm.isError ? (confirm.error as ApiError).message : null;

  return (
    <div className="min-h-screen bg-bg text-fg flex items-center justify-center px-6">
      <div className="w-full max-w-md">
        <header className="mb-8">
          <div className="text-label uppercase text-muted mb-2">Connect Claude Code</div>
          <h1 className="text-h1 text-fg">{label} wants to connect</h1>
          <p className="text-body text-muted mt-2">
            Choose the project this session connects to. Tessary issues a token scoped to that project:
            it can read, write, and use the tools of this one project. Manage it under MCP tokens in
            Settings. Code <code className="text-fg">{linkQuery.data!.userCode}</code>.
          </p>
        </header>

        <div className="space-y-5">
          <Field label="Organization" required>
            {(p) => (
              <Select {...p} value={orgSlug} onChange={(e) => setOrgSlug(e.target.value)}>
                {orgs.map((o) => (
                  <option key={o.id} value={o.slug}>{o.name}</option>
                ))}
              </Select>
            )}
          </Field>

          {!creating ? (
            <Field label="Project" required>
              {(p) => (
                <div className="flex gap-2">
                  <Select {...p} value={projectSlug} onChange={(e) => setProjectSlug(e.target.value)} className="flex-1">
                    {(projects.data ?? []).map((pr) => (
                      <option key={pr.id} value={pr.slug}>{pr.name}</option>
                    ))}
                    {(projects.data ?? []).length === 0 && <option value="">No projects yet</option>}
                  </Select>
                  <Button variant="secondary" onClick={() => setCreating(true)}>New project</Button>
                </div>
              )}
            </Field>
          ) : (
            <Field label="New project name" required>
              {(p) => (
                <div className="flex gap-2">
                  <Input
                    {...p}
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    placeholder="Support agent"
                    autoFocus
                    className="flex-1"
                  />
                  <Button
                    variant="primary"
                    onClick={() => createProject.mutate()}
                    disabled={!newName.trim()}
                    loading={createProject.isPending}
                  >
                    Create
                  </Button>
                  <Button variant="ghost" onClick={() => setCreating(false)}>Cancel</Button>
                </div>
              )}
            </Field>
          )}

          {confirmError && <div className="text-small text-error">{confirmError}</div>}

          <div className="flex gap-2">
            <Button
              variant="primary"
              className="flex-1"
              onClick={() => confirm.mutate()}
              disabled={!orgSlug || !projectSlug || creating}
              loading={confirm.isPending}
            >
              Connect
            </Button>
            <Button variant="secondary" onClick={() => deny.mutate()} loading={deny.isPending}>
              Decline
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Centered({ title, body, children }: { title: string; body: string; children?: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-bg text-fg flex items-center justify-center px-6">
      <div className="w-full max-w-md text-center">
        <h1 className="text-h1 text-fg">{title}</h1>
        <p className="text-body text-muted mt-2">{body}</p>
        {children}
      </div>
    </div>
  );
}
