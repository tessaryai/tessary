// SPDX-License-Identifier: Apache-2.0
// Opened in place from Settings, a case and an RCA report. A token leads because it works on every
// deployment; the App path needs one registered first. Token creation is explained here because those
// few lines are the whole distance to a working connection.
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, CircleAlert, ExternalLink } from "lucide-react";
import { useProjectApi } from "../../tenant/TenantContext";
import { ApiError } from "../../api/types";
import { Button, Field, Input, Modal, useToast } from "../../ui";

/** Null until both halves are present, which also gates submit. */
export function parseRepo(input: string): { owner: string; name: string } | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  const bare = trimmed
    .replace(/^[a-z][a-z0-9+.-]*:\/\//i, "")
    .replace(/^git@/i, "")
    .replace(/^www\./i, "")
    .replace(/^github\.com[/:]/i, "")
    .replace(/\.git$/i, "")
    .replace(/[/]+$/, "");
  const parts = bare.split("/").filter((p) => p.length > 0);
  if (parts.length < 2) return null;
  return { owner: parts[0], name: parts[1] };
}

/**
 * GitHub has no parameter for the repository picker, so the repository rides in `description`. GitHub
 * drops a `name` over 40 characters rather than truncating it.
 */
export function tokenTemplateUrl(repo: { owner: string; name: string } | null): string {
  const slug = repo ? `${repo.owner}/${repo.name}` : null;
  const params = new URLSearchParams();
  params.set("name", (slug ? `Tessary: ${slug}` : "Tessary").slice(0, 40));
  params.set(
    "description",
    slug
      ? `Read-only access for Tessary root-cause analysis. Under Repository access, select only ${slug}.`
      : "Read-only access for Tessary root-cause analysis.",
  );
  if (repo) params.set("target_name", repo.owner);
  params.set("expires_in", "90");
  params.set("contents", "read");
  return `https://github.com/settings/personal-access-tokens/new?${params.toString()}`;
}

export function ConnectRepositoryDialog({
  open,
  onClose,
  onUseGithubApp,
}: {
  open: boolean;
  onClose: () => void;
  onUseGithubApp?: () => void;
}) {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();

  const [repoInput, setRepoInput] = useState("");
  const [token, setToken] = useState("");
  const [helpOpen, setHelpOpen] = useState(false);

  const repo = parseRepo(repoInput);

  const connect = useMutation({
    mutationFn: () =>
      api.connectGit({
        provider: "github",
        repoOwner: repo?.owner ?? "",
        repoName: repo?.name ?? "",
        token: token.trim() === "" ? undefined : token.trim(),
      }),
    onSuccess: () => {
      setRepoInput("");
      setToken("");
      setHelpOpen(false);
      void qc.invalidateQueries({ queryKey: ["git-integration", api.base] });
      toast.success("Repository connected");
      onClose();
    },
  });

  // `||`, not `??`: an empty server message would otherwise render no alert.
  const error = connect.error ? (connect.error as ApiError).detail || "The request failed. Try again." : null;

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Connect repository"
      subtitle="Tessary reads this repository during RCA (root-cause analysis) to check what changed in your code."
      footer={
        <>
          {onUseGithubApp && (
            <Button variant="ghost" size="sm" className="mr-auto" onClick={onUseGithubApp}>
              Use a GitHub App instead
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            size="sm"
            loading={connect.isPending}
            disabled={connect.isPending || repo == null}
            onClick={() => connect.mutate()}
          >
            {connect.isPending ? "Connecting…" : "Connect repository"}
          </Button>
        </>
      }
    >
      <Field label="Repository">
        {(p) => (
          <Input
            {...p}
            value={repoInput}
            onChange={(e) => setRepoInput(e.target.value)}
            placeholder="owner/name or GitHub URL"
            autoFocus
          />
        )}
      </Field>

      <Field
        label="Access token"
        className="mt-4"
        hint="Tessary stores the token encrypted and uses it only to clone the repository."
      >
        {(p) => (
          <Input {...p} type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="" />
        )}
      </Field>

      <button
        type="button"
        onClick={() => setHelpOpen((v) => !v)}
        className="mt-2.5 flex items-center gap-1.5 text-small text-fg"
      >
        <ChevronRight
          size={11}
          strokeWidth={1.75}
          aria-hidden="true"
          className="text-muted transition-transform"
          style={{
            transform: helpOpen ? "rotate(90deg)" : "rotate(0deg)",
            transitionDuration: "var(--duration-micro)" }}
        />
        How to create a token
      </button>

      {helpOpen && (
        <div className="bg-raised rounded-card mt-2.5 px-4 py-3.5">
          <ol className="flex flex-col gap-3 m-0 p-0" style={{ listStyle: "none" }}>
            <Step n={1}>
              <div className="text-small text-fg-secondary">Create a fine-grained token on GitHub.</div>
              <Button
                variant="secondary"
                size="sm"
                className="mt-2"
                trailingIcon={<ExternalLink size={11} strokeWidth={1.75} aria-hidden="true" />}
                onClick={() => window.open(tokenTemplateUrl(repo), "_blank", "noopener,noreferrer")}
              >
                Create a token on GitHub
              </Button>
              <div className="text-small text-subtle mt-2">
                Opens GitHub with the resource owner, expiration, and <span className="font-mono">Contents: read</span>{" "}
                already set.
              </div>
            </Step>
            <Step n={2}>
              <div className="text-small text-fg-secondary">
                Under <span className="font-mono">Repository access</span>, select{" "}
                {repo ? <span className="font-mono">{`${repo.owner}/${repo.name}`}</span> : "your repository"}.
              </div>
            </Step>
            <Step n={3}>
              <div className="text-small text-fg-secondary">
                Select <span className="font-mono">Generate token</span>, copy it, and paste it above.
              </div>
            </Step>
          </ol>
          <div className="border-t border-border mt-3.5 pt-3 flex flex-col gap-2">
            <div className="text-small text-subtle">
              Tessary can't renew an expired token. RCA continues without repository access until you replace it.
            </div>
            <div className="text-small text-subtle">
              If the repository belongs to an organization, an organization owner approves the token before it works.
            </div>
          </div>
        </div>
      )}

      {error && (
        <div
          role="alert"
          className="rounded-card border border-[color:var(--color-error)] mt-4 px-3 py-2 flex items-start gap-2"
          style={{ backgroundColor: "var(--color-error-subtle)" }}
        >
          <CircleAlert size={13} strokeWidth={1.75} aria-hidden="true" className="text-error shrink-0 mt-0.5" />
          <span className="text-small text-fg-secondary">
            <span className="font-medium text-fg">Connection failed.</span> {error}
          </span>
        </div>
      )}
    </Modal>
  );
}

function Step({ n, children }: { n: number; children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-2.5">
      <span
        aria-hidden="true"
        className="rounded-pill border border-border text-label text-muted shrink-0 inline-flex items-center justify-center"
        style={{ width: 18, height: 18, marginTop: 1 }}
      >
        {n}
      </span>
      <div className="min-w-0">{children}</div>
    </li>
  );
}
