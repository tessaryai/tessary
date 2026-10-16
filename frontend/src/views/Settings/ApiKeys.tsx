// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useProjectApi } from "../../tenant/TenantContext";
import { ledgerTime } from "../../lib/ledgerTime";
import { ApiError } from "../../api/types";
import type { ApiKey, ApiKeyAudit, KeyScope } from "../../api/types-auth";
import {
  Badge,
  Button,
  Field,
  IconButton,
  Input,
  Modal,
  PageBody,
  PageHeader,
  Section,
  Select,
  Spinner,
  cn,
  useToast,
} from "../../ui";
import { IssuedFooter, IssuedSecret, IssuedTitle, NoSecrets, RevokeButton } from "./secretParts";

const SCOPES: { value: KeyScope; label: string; hint: string }[] = [
  { value: "write", label: "Write", hint: "Ingest traces (POST /v1/traces)." },
  { value: "query", label: "Query", hint: "Read API (POST /v1/query/*)." },
  { value: "admin", label: "Admin", hint: "Tool access over MCP (Model Context Protocol), plus write and query." },
];

const SCOPE_TONE: Record<KeyScope, "info" | "accent" | "success"> = {
  write: "info",
  query: "success",
  admin: "accent",
};

export function ApiKeys() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();

  const keys = useQuery({ queryKey: ["api-keys", api.base], queryFn: api.listApiKeys });
  const audit = useQuery({ queryKey: ["api-keys-audit", api.base], queryFn: api.listApiKeyAudit });

  const [modalOpen, setModalOpen] = useState(false);
  const [name, setName] = useState("");
  const [scope, setScope] = useState<KeyScope>("write");
  const [rotating, setRotating] = useState<ApiKey | null>(null);
  const [issued, setIssued] = useState<{ plaintext: string; name: string } | null>(null);

  const create = useMutation({
    mutationFn: () =>
      api.createApiKey({
        name: name.trim(),
        scope,
      }),
    onSuccess: (r) => {
      setIssued({ plaintext: r.plaintext, name: r.key.name });
      qc.invalidateQueries({ queryKey: ["api-keys", api.base] });
      qc.invalidateQueries({ queryKey: ["api-keys-audit", api.base] });
    },
    onError: (err) => toast.error("Could not create key", (err as ApiError).message),
  });

  const rotate = useMutation({
    mutationFn: (keyId: string) => api.rotateApiKey(keyId),
    onSuccess: (r) => {
      setIssued({ plaintext: r.plaintext, name: r.key.name });
      qc.invalidateQueries({ queryKey: ["api-keys", api.base] });
      qc.invalidateQueries({ queryKey: ["api-keys-audit", api.base] });
    },
    onError: (err) => toast.error("Could not rotate key", (err as ApiError).message),
  });

  const revoke = useMutation({
    mutationFn: (keyId: string) => api.revokeApiKey(keyId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["api-keys", api.base] });
      qc.invalidateQueries({ queryKey: ["api-keys-audit", api.base] });
      toast.success("Key revoked");
    },
    onError: (err) => toast.error("Could not revoke key", (err as ApiError).message),
  });

  function openCreate() {
    setRotating(null);
    setName("");
    setScope("write");
    setIssued(null);
    setModalOpen(true);
  }

  function openRotate(key: ApiKey) {
    setRotating(key);
    setName(key.name);
    setScope(key.scope);
    setIssued(null);
    setModalOpen(true);
  }

  function closeModal() {
    setModalOpen(false);
    setRotating(null);
    setName("");
    setScope("write");
    setIssued(null);
  }

  function submitForm() {
    if (rotating) rotate.mutate(rotating.id);
    else if (name.trim()) create.mutate();
  }

  const rows = keys.data ?? [];
  const isEmpty = keys.isSuccess && rows.length === 0;
  const pending = create.isPending || rotate.isPending;

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Security & access"
        title="API keys"
        subtitle="Keys for ingesting traces, querying results, and tool access, each scoped to this project. A key's plaintext is shown only once, at creation or rotation."
        actions={
          <Button variant="primary" onClick={openCreate}>
            New key
          </Button>
        }
      />

      <Section>
        {keys.isLoading ? (
          <div className="flex items-center gap-2 py-12 text-small text-muted">
            <Spinner size="sm" />
            Loading keys…
          </div>
        ) : keys.isError ? (
          <p className="text-small text-error">{(keys.error as ApiError)?.message ?? "Could not load keys. Try again."}</p>
        ) : isEmpty ? (
          <NoSecrets title="No keys yet" action="New key" onAction={openCreate}>
            Create one to ingest traces or query this project from your own code.
          </NoSecrets>
        ) : (
          <div className="overflow-hidden rounded-card border border-border">
            <div className="flex items-center gap-3 px-4 py-2.5 bg-surface border-b border-border text-column-header text-muted">
              <span className="flex-1">Name</span>
              <span className="w-16">Scope</span>
              <span className="w-24">Created</span>
              <span className="w-20">Last used</span>
              <span className="w-32 text-right" />
            </div>
            {rows.map((k) => (
              <KeyRow
                key={k.id}
                apiKey={k}
                onRotate={() => openRotate(k)}
                onRevoke={() => {
                  if (
                    confirm(
                      `Revoke "${k.name}"? Any client using it loses access immediately. This cannot be undone.`,
                    )
                  ) {
                    revoke.mutate(k.id);
                  }
                }}
                revoking={revoke.isPending && revoke.variables === k.id}
              />
            ))}
          </div>
        )}
      </Section>

      <Section title="Audit" subtitle="Recent create, rotate, and revoke activity for this project's keys.">
        {audit.isLoading ? (
          <div className="flex items-center gap-2 py-8 text-small text-muted">
            <Spinner size="sm" />
            Loading activity…
          </div>
        ) : audit.isError ? (
          <p className="text-small text-error">{(audit.error as ApiError)?.message ?? "Could not load activity. Try again."}</p>
        ) : (audit.data ?? []).length === 0 ? (
          <p className="py-6 text-small text-subtle">No activity yet.</p>
        ) : (
          <div className="overflow-hidden rounded-card border border-border">
            {(audit.data ?? []).map((entry) => (
              <AuditRow key={entry.id} entry={entry} />
            ))}
          </div>
        )}
      </Section>

      <Modal
        open={modalOpen}
        onClose={closeModal}
        size="sm"
        title={
          issued ? (
            <IssuedTitle>
              Key <span className="font-mono font-normal text-muted">{issued.name}</span>{" "}
              {rotating ? "rotated" : "created"}
            </IssuedTitle>
          ) : rotating ? (
            "Rotate key"
          ) : (
            "New key"
          )
        }
        subtitle={
          issued
            ? undefined
            : rotating
              ? "Issue a new secret for this key. The old secret stops working immediately."
              : "Choose what this key can do, then create it."
        }
        footer={
          issued ? (
            <IssuedFooter noun="key" onDone={closeModal} />
          ) : (
            <>
              <Button variant="ghost" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                variant="primary"
                disabled={!rotating && !name.trim()}
                loading={pending}
                onClick={submitForm}
              >
                {rotating ? "Rotate key" : "Create key"}
              </Button>
            </>
          )
        }
      >
        {issued ? (
          <IssuedSecret plaintext={issued.plaintext} noun="key" lost="rotate the key for a fresh secret" />
        ) : rotating ? (
          <div className="flex flex-col gap-3.5">
            <div className="rounded-control border border-border bg-surface px-3.5 py-3">
              <div className="text-label uppercase text-subtle">Key</div>
              <div className="mt-1 flex items-center gap-2 text-small text-fg">
                <span className="truncate">{rotating.name}</span>
                <Badge tone={SCOPE_TONE[rotating.scope]}>{rotating.scope}</Badge>
              </div>
              <div className="mt-1 font-mono text-label text-muted">{rotating.token_prefix}…</div>
            </div>
            <p className="text-small text-muted">
              Rotating keeps the name and scope but issues a new secret. Update every client that
              uses this key.
            </p>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            <Field label="Name" hint="A label to recognize it later, for example ingest-prod or ci-pipeline.">
              {(p) => (
                <Input
                  {...p}
                  value={name}
                  autoFocus
                  onChange={(e) => setName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && name.trim() && !pending) create.mutate();
                  }}
                  placeholder="ingest-prod"
                />
              )}
            </Field>
            <Field label="Scope" hint={SCOPES.find((s) => s.value === scope)?.hint}>
              {(p) => (
                <Select {...p} value={scope} onChange={(e) => setScope(e.target.value as KeyScope)}>
                  {SCOPES.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          </div>
        )}
      </Modal>
    </PageBody>
  );
}

function KeyRow({
  apiKey,
  onRotate,
  onRevoke,
  revoking,
}: {
  apiKey: ApiKey;
  onRotate: () => void;
  onRevoke: () => void;
  revoking: boolean;
}) {
  const revoked = !!apiKey.revoked_at;
  return (
    <div
      className={cn(
        "flex items-center gap-3 px-4 py-3 border-b border-border last:border-b-0",
        revoked && "opacity-70",
      )}
    >
      <span className="flex-1 min-w-0">
        <span className={cn("block truncate text-small", revoked ? "text-subtle line-through" : "text-fg")}>
          {apiKey.name}
        </span>
        <span className={cn("block font-mono text-label", revoked ? "text-subtle" : "text-muted")}>
          {apiKey.token_prefix}…
        </span>
      </span>
      <span className="w-16">
        <Badge tone={revoked ? "neutral" : SCOPE_TONE[apiKey.scope]}>{apiKey.scope}</Badge>
      </span>
      <span className="w-24 text-label text-muted">{ledgerTime(apiKey.created_at)}</span>
      <span className="w-20 text-label text-muted">{ledgerTime(apiKey.last_used_at)}</span>
      <span className="w-32 flex justify-end gap-1">
        {revoked ? (
          <Badge tone="neutral">Revoked</Badge>
        ) : revoking ? (
          <Spinner size="sm" />
        ) : (
          <>
            <IconButton
              label={`Rotate ${apiKey.name}`}
              size="sm"
              onClick={onRotate}
              className="text-muted hover:text-fg px-2 w-auto">
              <span className="text-label font-medium">Rotate</span>
            </IconButton>
            <RevokeButton name={apiKey.name} onRevoke={onRevoke} />
          </>
        )}
      </span>
    </div>
  );
}

function AuditRow({ entry }: { entry: ApiKeyAudit }) {
  const tone =
    entry.action === "created" ? "success" : entry.action === "revoked" ? "error" : "warning";
  return (
    <div className="flex items-center gap-3 px-4 py-2.5 border-b border-border last:border-b-0">
      <span className="w-20">
        <Badge tone={tone}>{entry.action}</Badge>
      </span>
      <span className="flex-1 min-w-0 truncate text-small text-fg">
        {entry.details ?? (entry.api_key_id ? `Key ${entry.api_key_id.slice(0, 8)}…` : "—")}
      </span>
      <span className="w-28 text-right text-label text-muted">{ledgerTime(entry.created_at)}</span>
    </div>
  );
}
