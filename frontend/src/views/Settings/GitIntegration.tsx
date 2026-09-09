// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Git integration.
 *
 * The repository connection is administration — a credential binding the whole
 * project depends on — so it lives in Settings with the other credentials rather
 * than on the Observer surface that happens to consume it.
 *
 * It is load-bearing for two things that fail without it — Observer has nothing to
 * compare graders against, and every Run RCA is refused because the sandboxed
 * agent has no repo to clone — and it UPGRADES a third. Layer-2 triage runs
 * either way: with a repo it rules a finding against the committed spec and the
 * code, and without one it rules from the finding's own trace evidence. The
 * difference is the strength of the ruling, not whether there is one, and the
 * Classifiers page says which lane it got rather than sending anyone here.
 *
 * Three connect paths, all server-verified:
 *   1. Install the GitHub App (the hosted or BYO redirect) — the backend
 *      callback returns with ?connected=1, with ?install_select=<token> when it
 *      resolved more than one candidate repo and a human has to pick, or with
 *      ?github_error=<code> when it failed. Every one of those used to land on
 *      the Setup page; that page is gone, and this is the only surface that
 *      binds a repository, so the callbacks land here.
 *   2. Bind an owner/name directly, for a repo the app already reaches — or,
 *      with a pasted personal access token, a repo no App installation
 *      reaches at all (the PAT fallback).
 *   3. No App configured anywhere yet: "Set up your own GitHub App" runs
 *      GitHub's manifest flow to register one interactively and store
 *      its credentials, landing back here with ?github_app_connected=1 —
 *      then path 1 installs it on the repos to observe.
 * The picker's choice is re-validated against the sealed token server-side, so
 * the list is a convenience and a tampered pick is rejected.
 */
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { useProjectApi } from "../../tenant/TenantContext";
import type { InstallationOption } from "../../api/types";
import { Button, ErrorNote, Field, Input, Modal, PageBody, PageHeader, Skeleton, useToast } from "../../ui";

export function GitIntegration() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [params, setParams] = useSearchParams();

  const integrationQ = useQuery({ queryKey: ["git-integration", api.base], queryFn: api.getGitIntegration });

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["git-integration", api.base] });
    void qc.invalidateQueries({ queryKey: ["observer-status", api.base] });
  };

  // The install callback lands back here. Refresh, say so once, and strip the flag
  // so a reload doesn't re-toast.
  const justConnected = params.get("connected") === "1";
  useEffect(() => {
    if (!justConnected) return;
    invalidate();
    toast.success("Repository connected", "RCA can now read this repository.");
    const next = new URLSearchParams(params);
    next.delete("connected");
    setParams(next, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [justConnected]);

  // The manifest wizard's callback lands back here once GitHub hands over the newly-registered
  // App's credentials and GithubAppConfigService has persisted + live-applied them. The App
  // is now configured deployment-wide, but THIS project still has no repo bound — nudge straight
  // into the install redirect the App-configured install-url now serves instead of dead-ending.
  const justSetUpApp = params.get("github_app_connected") === "1";
  useEffect(() => {
    if (!justSetUpApp) return;
    toast.success("GitHub App configured", "Install it on the repositories Tessary should read, then connect one.");
    const next = new URLSearchParams(params);
    next.delete("github_app_connected");
    setParams(next, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [justSetUpApp]);

  // A failed connect redirects back here with ?github_error=<code> rather than dying on a raw JSON
  // error page. Surface it and leave the user on the page that can retry.
  const githubError = params.get("github_error");
  useEffect(() => {
    if (!githubError) return;
    toast.error(
      "GitHub connection failed",
      `${githubError}. Try again, or select "Use an existing installation".`,
    );
    const next = new URLSearchParams(params);
    next.delete("github_error");
    setParams(next, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [githubError]);

  // More than one candidate repo behind the installation — the human picks.
  const selectToken = params.get("install_select");
  const optionsQ = useQuery({
    queryKey: ["git-installation-options", api.base, selectToken],
    queryFn: () => api.getGithubInstallationOptions(selectToken ?? ""),
    enabled: selectToken != null,
  });

  const [owner, setOwner] = useState("");
  const [name, setName] = useState("");
  const [token, setToken] = useState("");
  const [manualOpen, setManualOpen] = useState(false);

  const installRedirect = useMutation({
    mutationFn: api.getGithubInstallUrl,
    onSuccess: (res) => {
      window.location.href = res.url;
    },
    // No hosted app configured (self-hosted, or the app isn't set up): fall back
    // to binding a repo by name rather than dead-ending.
    onError: () => setManualOpen(true),
  });

  const authorizeRedirect = useMutation({
    mutationFn: api.getGithubAuthorizeUrl,
    onSuccess: (res) => {
      window.location.href = res.url;
    },
    onError: () => setManualOpen(true),
  });

  // GitHub's manifest flow needs a POSTed `manifest` form field — too large for a query string —
  // so this can't be a window.location.href redirect like the two above. Build a throwaway <form>,
  // fill it, submit it, and let the browser navigate away; nothing here runs after that.
  const manifestSetup = useMutation({
    mutationFn: api.getGithubManifestStart,
    onSuccess: (res) => {
      const form = document.createElement("form");
      form.method = "POST";
      form.action = res.url;
      form.style.display = "none";
      const field = document.createElement("input");
      field.type = "hidden";
      field.name = "manifest";
      field.value = res.manifest;
      form.appendChild(field);
      document.body.appendChild(form);
      form.submit();
    },
    onError: () => toast.error("Could not start GitHub App setup", 'Try again, or select "Enter a repository by name".'),
  });

  const connect = useMutation({
    mutationFn: () =>
      api.connectGit({
        provider: "github",
        repoOwner: owner.trim(),
        repoName: name.trim(),
        // A blank token binds a repo the hosted/BYO App already reaches, same as before. A non-blank
        // one is a personal-access-token fallback for a repo no App installation covers — the backend
        // seals it and GithubTokenService tries it before ever touching App credentials.
        token: token.trim() === "" ? undefined : token.trim(),
      }),
    onSuccess: () => {
      setOwner("");
      setName("");
      setToken("");
      setManualOpen(false);
      invalidate();
      toast.success("Repository connected");
    },
  });

  const select = useMutation({
    mutationFn: (o: InstallationOption) =>
      api.selectGithubInstallation({
        token: selectToken ?? "",
        installationId: o.installationId,
        repoOwner: o.owner,
        repoName: o.name,
      }),
    onSuccess: () => {
      const next = new URLSearchParams(params);
      next.delete("install_select");
      setParams(next, { replace: true });
      invalidate();
      toast.success("Repository connected");
    },
  });

  const disconnect = useMutation({
    mutationFn: api.disconnectGit,
    onSuccess: () => {
      invalidate();
      toast.success("Repository disconnected");
    },
  });

  const integ = integrationQ.data;

  return (
    <>
      <PageHeader
        eyebrow="Data & ingestion"
        title="Git integration"
        subtitle="The repository Tessary reads when it runs RCA (root-cause analysis). Without one, RCA still runs, but it rules on trace evidence alone and cannot say what changed in your code."
      />
      <PageBody>
        {integrationQ.isLoading && <Skeleton className="h-16 w-full" />}
        {integrationQ.isError && <ErrorNote error={integrationQ.error} />}

        {integrationQ.isSuccess && integ && (
          <div
            className="bg-surface border border-border py-4.5 px-5 gap-4"
            style={{ borderRadius: "var(--radius-card)", display: "flex", alignItems: "center" }}
          >
            <div className="min-w-0 flex-1">
              <div className="font-mono text-fg text-body">
                {integ.repoOwner}/{integ.repoName}
                <span className="text-muted">@{integ.defaultBranch}</span>
              </div>
              <div className="text-subtle mt-1.25 text-small">
                {integ.provider}
                {integ.host ? ` · ${integ.host}` : ""}
                {integ.observerCursorSha ? ` · synced at ${integ.observerCursorSha.slice(0, 7)}` : " · not synced yet"}
              </div>
            </div>
            <Button variant="ghost" size="sm" onClick={() => disconnect.mutate()} disabled={disconnect.isPending}>
              {disconnect.isPending ? "Disconnecting…" : "Disconnect"}
            </Button>
          </div>
        )}

        {integrationQ.isSuccess && !integ && (
          <div
            className="bg-surface border border-border py-5.5 px-5"
            style={{ borderRadius: "var(--radius-card)" }}
          >
            <p className="text-fg m-0 text-body">
              No repository connected
            </p>
            <p className="text-muted mt-2 mx-0 mb-0 text-body" style={{ maxWidth: 560 }}>
              Until one is connected, RCA can still run but rules on trace evidence alone and cannot check your code.
            </p>
            <div className="flex items-center gap-2 mt-4" style={{ flexWrap: "wrap" }}>
              <Button size="sm" onClick={() => installRedirect.mutate()} disabled={installRedirect.isPending}>
                {installRedirect.isPending ? "Opening GitHub…" : "Install the GitHub App"}
              </Button>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => authorizeRedirect.mutate()}
                disabled={authorizeRedirect.isPending}
              >
                Use an existing installation
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setManualOpen(true)}>
                Enter a repository by name
              </Button>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => manifestSetup.mutate()}
                disabled={manifestSetup.isPending}
              >
                {manifestSetup.isPending ? "Opening GitHub…" : "Set up your own GitHub App"}
              </Button>
            </div>
          </div>
        )}

        {disconnect.isError && <ErrorNote error={disconnect.error} />}
      </PageBody>

      <Modal open={manualOpen} onClose={() => setManualOpen(false)} title="Connect a repository">
        <p className="text-muted mt-0 mx-0 mb-4 text-body">
          Either the GitHub App already has access to this repository, or paste a personal access
          token below for a repository no App installation reaches. The token needs read access to
          code and metadata. Leave it blank to connect through the App instead.
        </p>
        <Field label="Owner">
          {(p) => <Input {...p} value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="tessaryai" autoFocus />}
        </Field>
        <Field label="Repository">
          {(p) => <Input {...p} value={name} onChange={(e) => setName(e.target.value)} placeholder="tessary" />}
        </Field>
        <Field label="Personal access token (optional)">
          {(p) => (
            <Input
              {...p}
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="ghp_… or github_pat_…"
            />
          )}
        </Field>
        {connect.isError && <ErrorNote error={connect.error} />}
        <div className="flex justify-end gap-2 mt-4.5">
          <Button variant="ghost" size="sm" onClick={() => setManualOpen(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={() => connect.mutate()}
            disabled={connect.isPending || owner.trim() === "" || name.trim() === ""}
          >
            {connect.isPending ? "Connecting…" : "Connect repository"}
          </Button>
        </div>
      </Modal>

      <Modal
        open={selectToken != null}
        onClose={() => {
          const next = new URLSearchParams(params);
          next.delete("install_select");
          setParams(next, { replace: true });
        }}
        title="Choose a repository"
      >
        <p className="text-muted mt-0 mx-0 mb-3.5 text-body">
          The installation reaches more than one repository. Choose the one this project's agent runs from.
        </p>
        {optionsQ.isLoading && <Skeleton className="h-10 w-full" />}
        {optionsQ.isError && <ErrorNote error={optionsQ.error} />}
        <div className="flex flex-col gap-0.25">
          {(optionsQ.data?.candidates ?? []).map((o) => (
            <button
              key={`${o.installationId}:${o.owner}/${o.name}`}
              type="button"
              onClick={() => select.mutate(o)}
              disabled={select.isPending}
              className="flex items-center w-full text-left bg-surface hover:bg-hover transition-colors gap-3 py-2.75 px-3.5"
              style={{ borderRadius: "var(--radius-control)" }}
            >
              <span className="font-mono text-fg min-w-0 flex-1 truncate text-code">
                {o.owner}/{o.name}
              </span>
              <span className="font-mono text-subtle shrink-0 text-label">
                {o.defaultBranch}
              </span>
            </button>
          ))}
        </div>
        {select.isError && <ErrorNote error={select.error} />}
      </Modal>
    </>
  );
}
