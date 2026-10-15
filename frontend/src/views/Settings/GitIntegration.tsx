// SPDX-License-Identifier: Apache-2.0
// The only surface that binds a repository, so the GitHub App install and manifest callbacks land
// here (?connected, ?install_select, ?github_error, ?github_app_connected). The picker's choice is
// re-validated server-side, so a tampered pick is rejected.
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { useProjectApi } from "../../tenant/TenantContext";
import type { InstallationOption } from "../../api/types";
import { Button, ErrorNote, Modal, PageBody, PageHeader, Skeleton, useToast } from "../../ui";
import { ConnectRepositoryDialog } from "../components/ConnectRepositoryDialog";

export function GitIntegration() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const [params, setParams] = useSearchParams();

  const integrationQ = useQuery({ queryKey: ["git-integration", api.base], queryFn: api.getGitIntegration });

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ["git-integration", api.base] });
  };

  // Strip the flag so a reload does not re-toast.
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

  // The App is now configured deployment-wide but this project has no repo, so go straight to install.
  const justSetUpApp = params.get("github_app_connected") === "1";
  useEffect(() => {
    if (!justSetUpApp) return;
    toast.success("GitHub App configured", "Install it on the repositories Tessary should read, then connect one.");
    const next = new URLSearchParams(params);
    next.delete("github_app_connected");
    setParams(next, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [justSetUpApp]);

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

  const selectToken = params.get("install_select");
  const optionsQ = useQuery({
    queryKey: ["git-installation-options", api.base, selectToken],
    queryFn: () => api.getGithubInstallationOptions(selectToken ?? ""),
    enabled: selectToken != null,
  });

  const [connectOpen, setConnectOpen] = useState(false);
  // A token works on every deployment; leading with the App sent self-hosters through dead buttons.
  const [appOpen, setAppOpen] = useState(false);

  const installRedirect = useMutation({
    mutationFn: api.getGithubInstallUrl,
    onSuccess: (res) => {
      window.location.href = res.url;
    },
    onError: () => setConnectOpen(true),
  });

  const authorizeRedirect = useMutation({
    mutationFn: api.getGithubAuthorizeUrl,
    onSuccess: (res) => {
      window.location.href = res.url;
    },
    onError: () => setConnectOpen(true),
  });

  // The manifest must be POSTed (too large for a query string), so submit a throwaway form.
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
    onError: () => toast.error("Could not start GitHub App setup", "Try again, or connect with an access token."),
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
      <PageBody size="narrow">
        <PageHeader
          eyebrow="Data & ingestion"
          title="Git integration"
          subtitle="The repository Tessary reads when it runs RCA (root-cause analysis). Without one, RCA still runs, but it rules on trace evidence alone and cannot say what changed in your code."
        />
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
              <Button size="sm" variant="primary" onClick={() => setConnectOpen(true)}>
                Connect repository
              </Button>
              {!appOpen && (
                <Button size="sm" variant="ghost" onClick={() => setAppOpen(true)}>
                  Use a GitHub App instead
                </Button>
              )}
            </div>

            {appOpen && (
              <div className="border-t border-border mt-4 pt-4">
                <div className="text-label uppercase text-subtle">GitHub App</div>
                <div className="flex items-center gap-2 mt-2.5" style={{ flexWrap: "wrap" }}>
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
          </div>
        )}

        {disconnect.isError && <ErrorNote error={disconnect.error} />}
      </PageBody>

      <ConnectRepositoryDialog
        open={connectOpen}
        onClose={() => setConnectOpen(false)}
        onUseGithubApp={() => {
          setConnectOpen(false);
          setAppOpen(true);
        }}
      />

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
