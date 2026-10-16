// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import { ledgerTime } from "../../lib/ledgerTime";
import { ApiError } from "../../api/types";
import type { McpTokenView } from "../../api/types-auth";
import {
  Badge,
  Button,
  Field,
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
import { IssuedFooter, IssuedSecret, IssuedTitle, NoSecrets, RevokeButton } from "./secretParts";

export function McpTokens() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();
  const tokens = useQuery({ queryKey: ["mcp-tokens", api.base], queryFn: api.listMcpTokens });

  const [modalOpen, setModalOpen] = useState(false);
  const [name, setName] = useState("");
  const [issued, setIssued] = useState<{ plaintext: string; name: string } | null>(null);
  // Not window.confirm(): an automated browser dismisses it unseen, so the revoke never runs (#84).
  const [toRevoke, setToRevoke] = useState<McpTokenView | null>(null);
  const copyConfig = useCopy();

  const issue = useMutation({
    mutationFn: () => api.issueMcpToken(name.trim()),
    onSuccess: (r) => {
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
          <NoSecrets title="No tokens yet" action="New token" onAction={openModal}>
            Issue one to connect Claude Code, then add it to your MCP config below.
          </NoSecrets>
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
                onRevoke={() => setToRevoke(t)}
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

      <Modal
        open={toRevoke !== null}
        onClose={() => setToRevoke(null)}
        size="sm"
        title={toRevoke && `Revoke "${toRevoke.name}"?`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setToRevoke(null)}>
              Cancel
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                revoke.mutate(toRevoke!.id);
                setToRevoke(null);
              }}
            >
              Revoke token
            </Button>
          </>
        }
      >
        <p className="text-small text-muted">Any client using it loses access immediately. You cannot undo this.</p>
      </Modal>

      <Modal
        open={modalOpen}
        onClose={closeModal}
        size="sm"
        title={
          issued ? (
            <IssuedTitle>
              Token <span className="font-mono font-normal text-muted">{issued.name}</span> created
            </IssuedTitle>
          ) : (
            "New token"
          )
        }
        subtitle={issued ? undefined : "Give it a name you'll recognize later, then issue it."}
        footer={
          issued ? (
            <IssuedFooter noun="token" onDone={closeModal} />
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
          <IssuedSecret plaintext={issued.plaintext} noun="token" lost="revoke it and issue a new one" />
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
      <span className="w-28 text-label text-muted">{ledgerTime(token.created_at)}</span>
      <span className="w-24 text-label text-muted">{ledgerTime(token.last_used_at)}</span>
      <span className="w-20 flex justify-end">
        {revoked ? (
          <Badge tone="neutral">Revoked</Badge>
        ) : revoking ? (
          <Spinner size="sm" />
        ) : (
          <RevokeButton name={token.name} onRevoke={onRevoke} />
        )}
      </span>
    </div>
  );
}
