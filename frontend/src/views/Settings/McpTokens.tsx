// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, KeyRound } from "lucide-react";
import { useProjectApi } from "../../tenant/TenantContext";
import { ApiError } from "../../api/types";
import type { McpTokenView } from "../../api/types-auth";
import {
  Badge,
  Button,
  CopyButton,
  Field,
  IconButton,
  Input,
  Modal,
  PageBody,
  PageHeader,
  Section,
  Spinner,
  cn,
  useCopy,
  useToast,
} from "../../ui";

/**
 * Settings → MCP tokens.
 *
 * The whole screen exists for one fragile moment: the secret you can only see
 * once. So that reveal gets the design weight, a focused modal with a single
 * copy action, an honest warning, and a deliberate "I've saved it" before it's
 * gone for good. The rest is a quiet ledger of prefixes and last-used times.
 *
 * Data wiring is preserved verbatim: the `mcp-tokens` query plus the issue and
 * revoke mutations, all keyed on `api.base`.
 */


function KeyGlyph({ size = 20 }: { size?: number }) {
  return <KeyRound size={size} strokeWidth={1.5} aria-hidden="true" />;
}

function relativeTime(iso: string | null): string {
  if (!iso) return "Never";
  const then = new Date(iso).getTime();
  const diff = Date.now() - then;
  if (diff < 0) return new Date(iso).toLocaleString();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}

export function McpTokens() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const tokens = useQuery({ queryKey: ["mcp-tokens", api.base], queryFn: api.listMcpTokens });

  // Modal lifecycle: closed → naming (issue form) → reveal (one-time secret).
  const [modalOpen, setModalOpen] = useState(false);
  const [name, setName] = useState("");
  const [issued, setIssued] = useState<{ plaintext: string; name: string } | null>(null);
  // The MCP config block is a text link, not a Button, so it drives the same confirmed/reset cycle itself.
  const copyConfig = useCopy();

  const issue = useMutation({
    mutationFn: () => api.issueMcpToken(name.trim()),
    onSuccess: (r) => {
      // IssueTokenResponse: { token, plaintext, warning }. Reveal the plaintext once.
      setIssued({ plaintext: r.plaintext, name: r.token.name });
      qc.invalidateQueries({ queryKey: ["mcp-tokens", api.base] });
    },
    onError: (err) => toast.error("Could not issue token", (err as ApiError).message),
  });

  const revoke = useMutation({
    mutationFn: (id: string) => api.revokeMcpToken(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["mcp-tokens", api.base] });
      toast.success("Token revoked");
    },
    onError: (err) => toast.error("Could not revoke token", (err as ApiError).message),
  });

  function openModal() {
    setName("");
    setIssued(null);
    setModalOpen(true);
  }

  function closeModal() {
    setModalOpen(false);
    setName("");
    setIssued(null);
  }

  const mcpConfig = JSON.stringify(
    {
      mcpServers: {
        tessary: {
          type: "http",
          url: window.location.origin + "/mcp",
          headers: { Authorization: "Bearer $TESSARY_TOKEN" },
        },
      },
    },
    null,
    2,
  );


  const rows = tokens.data ?? [];
  const isEmpty = tokens.isSuccess && rows.length === 0;

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Security & access"
        title="MCP tokens"
        subtitle="Personal MCP (Model Context Protocol) tokens that let the evals plugin and Claude Code push bundles into this project. Each token is scoped to this project, and its plaintext is shown only once."
        actions={
          <Button variant="primary" onClick={openModal}>
            New token
          </Button>
        }
      />

      <Section>
        {tokens.isLoading ? (
          <div className="flex items-center gap-2 py-12 text-small text-muted">
            <Spinner size="sm" />
            Loading tokens…
          </div>
        ) : tokens.isError ? (
          <p className="text-small text-error">
            {(tokens.error as ApiError)?.message ?? "Could not load tokens. Try again."}
          </p>
        ) : isEmpty ? (
          <div className="flex flex-col items-center text-center py-14">
            <span className="flex size-[42px] items-center justify-center rounded-card bg-surface border border-border-strong text-muted">
              <KeyGlyph />
            </span>
            <div className="text-h3 text-fg mt-4">No tokens yet</div>
            <p className="text-small text-muted mt-2 max-w-sm">
              Issue one to connect Claude Code, then add it to your MCP config below.
            </p>
            <Button variant="primary" className="mt-5" onClick={openModal}>
              New token
            </Button>
          </div>
        ) : (
          <div className="overflow-hidden rounded-card border border-border">
            <div className="flex items-center gap-3 px-4 py-2.5 bg-surface border-b border-border text-column-header text-muted">
              <span className="flex-1">Name</span>
              <span className="w-32">Prefix</span>
              <span className="w-28">Created</span>
              <span className="w-24">Last used</span>
              <span className="w-20 text-right" />
            </div>
            {rows.map((t) => (
              <TokenRow
                key={t.id}
                token={t}
                onRevoke={() => {
                  if (confirm(`Revoke "${t.name}"? Any client using it loses access immediately. This cannot be undone.`)) {
                    revoke.mutate(t.id);
                  }
                }}
                revoking={revoke.isPending && revoke.variables === t.id}
              />
            ))}
          </div>
        )}
      </Section>

      {!isEmpty && (
        <Section title="MCP config" subtitle="Add this to your client, then restart Claude Code.">
          <div className="overflow-hidden rounded-card border border-border">
            <div className="flex items-center gap-3 px-4 py-2.5 bg-surface border-b border-border">
              <span className="flex-1 font-mono text-label text-muted">~/.claude/mcp.json</span>
              <button
                type="button"
                onClick={() => {
                  void copyConfig.copy(mcpConfig).then((ok) => {
                    if (ok) toast.success("Config copied");
                    else toast.error("Could not copy", "Copy the config manually.");
                  });
                }}
                className="text-label text-accent hover:text-accent-hover transition-colors"
                style={{ transitionDuration: "var(--duration-micro)" }}
              >
                {copyConfig.copied ? "Copied" : "Copy"}
              </button>
            </div>
            <pre className="px-4 py-3.5 font-mono text-small text-fg leading-relaxed whitespace-pre-wrap overflow-x-auto">
              {mcpConfig}
            </pre>
          </div>
          <div className="mt-3 flex items-center gap-2.5 text-small text-muted">
            <span>
              Set <code className="font-mono text-fg">TESSARY_TOKEN</code> to the secret you copied when issuing a token.
            </span>
          </div>
        </Section>
      )}

      {/* New token: name → one-time reveal, in a single focused modal. */}
      <Modal
        open={modalOpen}
        onClose={closeModal}
        size="sm"
        title={
          issued ? (
            <span className="flex items-center gap-2.5">
              <span className="flex size-6 items-center justify-center rounded-pill bg-accent-subtle border border-accent-edge text-accent">
                <KeyGlyph size={13} />
              </span>
              <span>
                Token <span className="font-mono font-normal text-muted">{issued.name}</span> created
              </span>
            </span>
          ) : (
            "New token"
          )
        }
        subtitle={issued ? undefined : "Give it a name you'll recognize later, then issue it."}
        footer={
          issued ? (
            <div className="flex w-full items-center gap-3">
              <Button variant="primary" onClick={closeModal}>
                Done
              </Button>
              <span className="ml-auto text-label text-subtle">Closing without copying loses the token.</span>
            </div>
          ) : (
            <>
              <Button variant="ghost" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                variant="primary"
                disabled={!name.trim()}
                loading={issue.isPending}
                onClick={() => issue.mutate()}
              >
                Issue token
              </Button>
            </>
          )
        }
      >
        {issued ? (
          <div>
            <div className="text-label uppercase text-muted">Copy it now. It won't be shown again.</div>
            <div className="mt-2.5 flex items-center gap-2.5 rounded-control border border-accent-edge bg-bg px-3.5 py-3">
              <span className="flex-1 min-w-0 font-mono text-small text-fg truncate">{issued.plaintext}</span>
              <CopyButton
                value={issued.plaintext}
                size="sm"
                variant="primary"
                className="flex-none"
                onCopyFailed={() => toast.error("Could not copy", "Copy the token manually.")}
              />
            </div>
            <div className="mt-3.5 flex items-start gap-2.5 rounded-control border border-warning bg-warning-subtle px-3.5 py-2.5">
              <AlertTriangle size={14} strokeWidth={1.5} aria-hidden="true" className="mt-px flex-none text-warning" />
              <span className="text-label text-fg leading-snug">
                Tessary stores only a hash. If you lose this, revoke it and issue a new one. There's no recovery.
              </span>
            </div>
          </div>
        ) : (
          <Field label="Name" hint="A label to recognize it later, for example my-laptop or ci-pipeline.">
            {(p) => (
              <Input
                {...p}
                value={name}
                autoFocus
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && name.trim() && !issue.isPending) issue.mutate();
                }}
                placeholder="ci-pipeline"
              />
            )}
          </Field>
        )}
      </Modal>
    </PageBody>
  );
}

function TokenRow({
  token,
  onRevoke,
  revoking,
}: {
  token: McpTokenView;
  onRevoke: () => void;
  revoking: boolean;
}) {
  const revoked = !!token.revoked_at;
  return (
    <div
      className={cn(
        "flex items-center gap-3 px-4 py-3 border-b border-border last:border-b-0",
        revoked && "opacity-70",
      )}
    >
      <span className={cn("flex-1 min-w-0 truncate text-small", revoked ? "text-subtle line-through" : "text-fg")}>
        {token.name}
      </span>
      <span className={cn("w-32 font-mono text-label", revoked ? "text-subtle" : "text-muted")}>
        {token.token_prefix}…
      </span>
      <span className="w-28 text-label text-muted">{relativeTime(token.created_at)}</span>
      <span className="w-24 text-label text-muted">{token.last_used_at ? relativeTime(token.last_used_at) : "Never"}</span>
      <span className="w-20 flex justify-end">
        {revoked ? (
          <Badge tone="neutral">Revoked</Badge>
        ) : revoking ? (
          <Spinner size="sm" />
        ) : (
          <IconButton
            label={`Revoke ${token.name}`}
            size="sm"
            onClick={onRevoke}
            className="text-error hover:text-error hover:bg-[color:var(--color-error-subtle)] px-2 w-auto">
            <span className="text-label font-medium">Revoke</span>
          </IconButton>
        )}
      </span>
    </div>
  );
}
