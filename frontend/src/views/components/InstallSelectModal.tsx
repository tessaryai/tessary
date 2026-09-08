// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import { ApiError } from "../../api/types";
import { Button, EmptyState, Input, Modal, Spinner } from "../../ui";

/**
 * Repo picker for the "connect an existing GitHub installation" flow. When the App is already
 * installed on the account and it grants access to more than one repo (or the user administers
 * more than one installation), the backend callback can't auto-pick, so it redirects the SPA
 * here with a sealed ?install_select=<token> carrying the verified-administered installation ids.
 *
 * This lives on the ungated Setup surface (NOT Observer): a fresh project hasn't graduated
 * onboarding, so a gated route would bounce the picker to Overview and drop the token before it
 * could render — and connecting GitHub is precisely what graduates the project. Setup is where the
 * rest of the GitHub install steps already live.
 */
export function InstallSelectModal({ token, onConnected }: { token: string | null; onConnected: () => void }) {
  const api = useProjectApi();
  const [filter, setFilter] = useState("");

  const options = useQuery({
    queryKey: ["github-install-options", api.base, token],
    queryFn: () => api.getGithubInstallationOptions(token as string),
    enabled: token != null,
    retry: false,
  });

  const select = useMutation({
    mutationFn: (opt: { installationId: number; owner: string; name: string }) =>
      api.selectGithubInstallation({
        token: token as string,
        installationId: opt.installationId,
        repoOwner: opt.owner,
        repoName: opt.name,
      }),
    onSuccess: onConnected,
  });

  const candidates = options.data?.candidates ?? [];
  const q = filter.trim().toLowerCase();
  const shown = q ? candidates.filter((c) => `${c.owner}/${c.name}`.toLowerCase().includes(q)) : candidates;

  return (
    <Modal
      open={token != null}
      onClose={onConnected}
      title="Choose a repository"
      subtitle="Choose the repository this project reads from. The same repository can be connected to more than one project."
      size="md"
    >
      {options.isLoading ? (
        <div className="flex items-center gap-2 text-small text-muted py-2">
          <Spinner size="sm" /> Loading repositories…
        </div>
      ) : options.isError ? (
        <p className="text-small text-error">
          <code className="font-mono">{(options.error as ApiError).code}</code>: {(options.error as ApiError).message}{" "}
          The selection link may have expired. Restart from Connect GitHub.
        </p>
      ) : candidates.length === 0 ? (
        <EmptyState
          title="No repositories available"
          body="The installation grants access to no repositories. Grant it access on GitHub, then restart from Connect GitHub."
        />
      ) : (
        <div className="space-y-3">
          {candidates.length > 6 && (
            <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter owner/repo…" />
          )}
          <div className="max-h-72 overflow-y-auto divide-y divide-border rounded-card border border-border">
            {shown.map((c) => {
              const busy =
                select.isPending &&
                select.variables?.installationId === c.installationId &&
                select.variables?.owner === c.owner &&
                select.variables?.name === c.name;
              return (
                <div
                  key={`${c.installationId}:${c.owner}/${c.name}`}
                  className="flex items-center justify-between gap-3 px-3 py-2">
                  <span className="text-small font-mono truncate">
                    {c.owner}/{c.name}
                  </span>
                  <Button
                    size="sm"
                    disabled={select.isPending}
                    onClick={() => select.mutate({ installationId: c.installationId, owner: c.owner, name: c.name })}
                  >
                    {busy ? "Connecting…" : "Connect"}
                  </Button>
                </div>
              );
            })}
            {shown.length === 0 && (
              <div className="px-3 py-2 text-small text-muted">No repositories match "{filter}".</div>
            )}
          </div>
          {select.isError && (
            <p className="text-small text-error">
              <code className="font-mono">{(select.error as ApiError).code}</code>: {(select.error as ApiError).message}
            </p>
          )}
        </div>
      )}
    </Modal>
  );
}
