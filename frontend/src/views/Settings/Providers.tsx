// SPDX-License-Identifier: Apache-2.0
import { useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertCircle } from "lucide-react";
import { useOrgApi } from "../../tenant/TenantContext";
import {
  ApiError,
  type CatalogEntry,
  type PlatformDescriptor,
  type ProviderCredentialView,
  type ModelProvider,
  type UpsertProviderCredentialRequest,
} from "../../api/types";
import {
  Button,
  Field,
  Input,
  Modal,
  PageBody,
  PageHeader,
  Skeleton,
  cn,
  useToast,
} from "../../ui";

/**
 * Settings, Providers: a calm credential roster for the judge models your
 * graders run on. The only thing you scan for is configured-vs-not: a status
 * dot carries the signal, model counts sit quietly underneath.
 *
 * The judgment moment is a rejected credential: when a save fails, the modal
 * surfaces the provider's own error and offers "Edit and retry".
 *
 * All data wiring is preserved: the catalog + credentials queries, and the
 * upsert/remove mutations, are unchanged.
 */

/** What credential shape a not-yet-configured provider expects, read aloud. */
function expectedFields(p: PlatformDescriptor): string {
  if (p.auth === "aws") return "Access key · secret · region";
  return p.supports_base_url ? "API key · base URL" : "API key";
}

export function Providers() {
  const api = useOrgApi();
  const qc = useQueryClient();
  const toast = useToast();

  const catalog = useQuery({ queryKey: ["provider-catalog", api.base], queryFn: api.listProviderCatalog });
  const credentials = useQuery({ queryKey: ["provider-credentials", api.base], queryFn: api.listProviderCredentials });

  const [editing, setEditing] = useState<ModelProvider | null>(null);

  const credByProvider = useMemo(() => {
    const m = new Map<ModelProvider, ProviderCredentialView>();
    for (const c of credentials.data?.credentials ?? []) m.set(c.provider, c);
    return m;
  }, [credentials.data]);

  const modelsByProvider = useMemo(() => {
    const m = new Map<ModelProvider, CatalogEntry[]>();
    for (const e of catalog.data?.models ?? []) {
      const list = m.get(e.provider) ?? [];
      list.push(e);
      m.set(e.provider, list);
    }
    return m;
  }, [catalog.data]);

  const remove = useMutation({
    mutationFn: (provider: ModelProvider) => api.deleteProviderCredential(provider),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["provider-credentials", api.base] });
      toast.success("Provider key removed");
    },
    onError: (err) => toast.error("Could not remove key", (err as ApiError).message),
  });

  const platform = editing ? catalog.data?.platforms.find((p) => p.id === editing) : undefined;

  const loading = catalog.isLoading || credentials.isLoading;
  const platforms = catalog.data?.platforms ?? [];

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Data & ingestion"
        title="Providers"
        subtitle="Keys for the model providers you bring. Every project in this organization shares them, and runs are billed to you."
      />

      {loading ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-[92px] rounded-card" />
          ))}
        </div>
      ) : catalog.isError || credentials.isError ? (
        <p className="text-small text-error">
          {((catalog.error ?? credentials.error) as ApiError)?.message ??
            "Could not load the provider catalog. Try again."}
        </p>
      ) : (
        // The "Free judge included" banner named Ollama, the platform's one no-key
        // provider — dropped by the maker filter (Amazon/Meta are not supported makers). Every
        // provider now needs an org key, so there is nothing left to point that banner at.
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {platforms.map((p) => (
            <ProviderRow
              key={p.id}
              platform={p}
              cred={credByProvider.get(p.id)}
              models={modelsByProvider.get(p.id) ?? []}
              onEdit={() => setEditing(p.id)}
              onRemove={() => {
                if (
                  confirm(
                    `Remove the stored ${p.label} key? Runs on this provider fail until you add a new key.`,
                  )
                )
                  remove.mutate(p.id);
              }}
              removing={remove.isPending && remove.variables === p.id}
            />
          ))}
        </div>
      )}

      {editing && platform && (
        <ProviderKeyModal
          platform={platform}
          existing={credByProvider.get(editing)}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            qc.invalidateQueries({ queryKey: ["provider-credentials", api.base] });
            toast.success(`${platform.label} key saved`);
          }}
        />
      )}
    </PageBody>
  );
}

/**
 * One provider in the roster. Configured-vs-not is the dominant signal: a
 * status dot + word, with the model count and a couple of names as quiet
 * support. No nested cards, a single bordered tile, structured by space.
 */
function ProviderRow({
  platform,
  cred,
  models,
  onEdit,
  onRemove,
  removing,
}: {
  platform: PlatformDescriptor;
  cred: ProviderCredentialView | undefined;
  models: CatalogEntry[];
  onEdit: () => void;
  onRemove: () => void;
  removing: boolean;
}) {
  const configured = !!cred && (cred.has_api_key || cred.has_aws_credentials);
  const names = models.map((m) => m.display_name);
  const namePreview = names.slice(0, 2).join(", ");

  return (
    <div className="flex flex-col justify-between rounded-card border border-border p-4">
      <div className="flex items-start justify-between gap-3">
        <span className={cn("text-body font-medium", configured ? "text-fg" : "text-muted")}>
          {platform.label}
        </span>
        {configured ? (
          <span className="flex items-center gap-1.5 text-label uppercase text-success">
            <span className="h-1.5 w-1.5 rounded-pill bg-success" aria-hidden="true" />
            Configured
          </span>
        ) : (
          <span className="text-label uppercase text-subtle">Not configured</span>
        )}
      </div>

      <div className="mt-2.5 text-label text-muted">
        {configured ? (
          <span className="font-mono text-subtle">Key stored</span>
        ) : (
          <span className="text-subtle">{expectedFields(platform)}</span>
        )}
      </div>

      <div className="mt-3 flex items-center justify-between gap-3">
        <span className="min-w-0 truncate text-label text-subtle">
          {models.length} model{models.length === 1 ? "" : "s"}
          {namePreview ? ` · ${namePreview}${names.length > 2 ? "…" : ""}` : " available"}
        </span>
        <div className="flex shrink-0 items-center gap-1">
          {configured ? (
            <>
              <button
                type="button"
                onClick={onEdit}
                className="rounded-control px-2 py-1 text-label text-muted transition-colors hover:bg-hover hover:text-fg">
                Edit key
              </button>
              <button
                type="button"
                onClick={onRemove}
                disabled={removing}
                className="rounded-control px-2 py-1 text-label text-muted transition-colors hover:bg-hover hover:text-error disabled:opacity-50">
                {removing ? "Removing…" : "Remove"}
              </button>
            </>
          ) : (
            <button
              type="button"
              onClick={onEdit}
              className="h-7 rounded-control border border-accent-edge bg-accent-subtle px-2.5 text-label text-accent transition-colors hover:bg-[color:var(--color-accent-edge)]">
              Add key
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function SectionLabel({ children }: { children: ReactNode }) {
  return (
    <div className="col-span-2 mt-1 border-t border-border pt-3 text-label uppercase text-muted">
      {children}
    </div>
  );
}

function ProviderKeyModal({
  platform,
  existing,
  onCancel,
  onSaved,
}: {
  platform: PlatformDescriptor;
  existing: ProviderCredentialView | undefined;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const api = useOrgApi();

  const auth = platform.auth;
  const isAws = auth === "aws";
  // CUSTOM has no real per-model catalog — one representative entry stands for "any model
  // this endpoint serves" — so this is the only place a project names the actual model id it wants.
  const isCustom = platform.id === "CUSTOM";

  const [baseUrl, setBaseUrl] = useState(existing?.base_url_override ?? "");
  const [apiKey, setApiKey] = useState("");
  const [awsRegion, setAwsRegion] = useState(existing?.aws_region ?? "us-east-1");
  const [awsAccess, setAwsAccess] = useState("");
  const [awsSecret, setAwsSecret] = useState("");
  const [bedrockArn, setBedrockArn] = useState(existing?.bedrock_model_arn ?? "");
  const [customModelName, setCustomModelName] = useState(existing?.custom_model_name ?? "");
  // Bedrock/mantle-only. Defaults to the stored value, else "api_key" — the same default the
  // backend applies to a credential with no auth_mode column value (ProviderCredentialController).
  const [authMode, setAuthMode] = useState<"api_key" | "iam_role">(
    existing?.auth_mode === "iam_role" ? "iam_role" : "api_key",
  );
  const usesIamRole = isAws && authMode === "iam_role";

  const save = useMutation({
    mutationFn: () => {
      // Omit a secret field (undefined) to keep the stored value; send a string to set it.
      const body: UpsertProviderCredentialRequest = {
        base_url_override: platform.supports_base_url ? baseUrl.trim() : undefined,
        api_key: !isAws ? (apiKey.trim() ? apiKey.trim() : undefined) : undefined,
        aws_region: isAws ? awsRegion.trim() : undefined,
        // An IAM-role credential has no AWS keys of its own — the instance/task role IS the
        // credential — so these are sent as undefined (leave-untouched) rather than blank
        // (explicitly clear) whenever that mode is selected, same as any other omitted field.
        aws_access_key: isAws && !usesIamRole ? (awsAccess.trim() ? awsAccess.trim() : undefined) : undefined,
        aws_secret_key: isAws && !usesIamRole ? (awsSecret.trim() ? awsSecret.trim() : undefined) : undefined,
        bedrock_model_arn: isAws ? bedrockArn.trim() : undefined,
        auth_mode: isAws ? authMode : undefined,
        custom_model_name: isCustom ? (customModelName.trim() ? customModelName.trim() : undefined) : undefined,
      };
      return api.upsertProviderCredential(platform.id, body);
    },
    onSuccess: () => onSaved(),
  });

  const hasStoredSecret = isAws ? existing?.has_aws_credentials : existing?.has_api_key;
  const errorMessage = save.isError ? (save.error as ApiError).message ?? "Could not save" : null;

  return (
    <Modal
      open
      onClose={onCancel}
      title={`${hasStoredSecret ? "Edit key" : "Add key"} · ${platform.label}`}
      size="lg"
      footer={
        <>
          <Button variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
          <Button variant="primary" loading={save.isPending} onClick={() => save.mutate()}>
            {hasStoredSecret ? "Save changes" : "Test and save"}
          </Button>
        </>
      }
    >
      <p className="mb-4 text-small text-muted">Stored encrypted. Used only to call this provider's models.</p>

      <div className="grid grid-cols-2 gap-3">
        {auth === "api_key" && (
          <>
            <SectionLabel>Connection</SectionLabel>
            <div className="col-span-2">
              <Field
                label="API key (required)"
                hint={
                  hasStoredSecret
                    ? "A key is already stored. Leave blank to keep it, or enter a new one to replace it."
                    : "Required. Runs on this provider use your credentials, not Tessary's."
                }
              >
                {(p) => (
                  <Input
                    {...p}
                    type="password"
                    value={apiKey}
                    onChange={(e) => setApiKey(e.target.value)}
                    placeholder={hasStoredSecret ? "•••••••• (stored)" : ""}
                    className="font-mono"
                  />
                )}
              </Field>
            </div>
            {platform.supports_base_url && (
              <div className="col-span-2">
                <Field
                  label={isCustom ? "Base URL (required)" : "Base URL override"}
                  hint={
                    isCustom
                      ? "Where this OpenAI-compatible endpoint is reachable."
                      : "Optional. Leave blank to use the platform default."
                  }
                >
                  {(p) => (
                    <Input
                      {...p}
                      value={baseUrl}
                      onChange={(e) => setBaseUrl(e.target.value)}
                      placeholder={platform.default_base_url ?? "https://…"}
                      className="font-mono"
                    />
                  )}
                </Field>
              </div>
            )}
            {isCustom && (
              <div className="col-span-2">
                <Field label="Custom model name" hint="The exact model id this endpoint expects, e.g. llama-3.3-70b.">
                  {(p) => (
                    <Input
                      {...p}
                      value={customModelName}
                      onChange={(e) => setCustomModelName(e.target.value)}
                      placeholder="my-model-id"
                      className="font-mono"
                    />
                  )}
                </Field>
              </div>
            )}
          </>
        )}

        {/* auth === "none" (Ollama's base-URL-only form) lived here until Ollama was dropped —
            "none" left PlatformAuth's union with it, so this branch is now unreachable and removed
            rather than dead code kept around. */}

        {auth === "aws" && (
          <>
            <SectionLabel>AWS credentials</SectionLabel>
            <Field label="AWS region">
              {(p) => <Input {...p} value={awsRegion} onChange={(e) => setAwsRegion(e.target.value)} className="font-mono" />}
            </Field>
            <Field label="Inference profile ARN" hint="The ARN (Amazon Resource Name) of the inference profile. Optional, but required for marketplace models.">
              {(p) => (
                <Input
                  {...p}
                  value={bedrockArn}
                  onChange={(e) => setBedrockArn(e.target.value)}
                  placeholder="arn:aws:bedrock:…"
                  className="font-mono"
                />
              )}
            </Field>

            {/* API key (sealed access/secret keys) vs IAM role (the sandbox/host's own
                instance or task role — no keys stored at all). An explicit opt-in, never inferred
                from blank key fields: see ChatModelFactory#byoOrIamAwsCredentials for why. */}
            <div className="col-span-2">
              <Field label="Auth mode">
                {(p) => (
                  <div {...p} className="flex gap-1.5 rounded-control border border-border p-1">
                    <button
                      type="button"
                      onClick={() => setAuthMode("api_key")}
                      className={cn(
                        "flex-1 rounded-control px-2.5 py-1.5 text-label transition-colors",
                        authMode === "api_key" ? "bg-accent-subtle text-accent" : "text-muted hover:bg-hover",
                      )}
                    >
                      Access + secret key
                    </button>
                    <button
                      type="button"
                      onClick={() => setAuthMode("iam_role")}
                      className={cn(
                        "flex-1 rounded-control px-2.5 py-1.5 text-label transition-colors",
                        authMode === "iam_role" ? "bg-accent-subtle text-accent" : "text-muted hover:bg-hover",
                      )}
                    >
                      IAM role
                    </button>
                  </div>
                )}
              </Field>
            </div>

            {usesIamRole ? (
              <div className="col-span-2 text-small text-muted">
                Runs on this host's own AWS identity (an instance or task role) — no keys are stored.
              </div>
            ) : (
              <>
                <div className="col-span-2">
                  <Field
                    label="AWS access key"
                    hint={hasStoredSecret ? "Keys already stored. Leave blank to keep them." : undefined}
                  >
                    {(p) => (
                      <Input
                        {...p}
                        type="password"
                        value={awsAccess}
                        onChange={(e) => setAwsAccess(e.target.value)}
                        placeholder={hasStoredSecret ? "•••••••• (stored)" : "AKIA…"}
                        className="font-mono"
                      />
                    )}
                  </Field>
                </div>
                <div className="col-span-2">
                  <Field label="AWS secret key">
                    {(p) => (
                      <Input
                        {...p}
                        type="password"
                        value={awsSecret}
                        onChange={(e) => setAwsSecret(e.target.value)}
                        placeholder={hasStoredSecret ? "•••••••• (stored)" : ""}
                        className="font-mono"
                      />
                    )}
                  </Field>
                </div>
              </>
            )}
          </>
        )}
      </div>

      {errorMessage && (
        <div className="mt-4 flex items-start gap-3 rounded-control border border-error bg-error-subtle px-3.5 py-3">
          <AlertCircle size={15} strokeWidth={1.4} aria-hidden="true" className="mt-0.5 shrink-0 text-error" />
          <div className="min-w-0 flex-1">
            <div className="text-small font-medium text-fg">The provider rejected this key</div>
            <div className="mt-1 break-words text-small text-muted">{errorMessage}</div>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button size="sm" variant="secondary" onClick={() => save.mutate()} loading={save.isPending}>
                Edit and retry
              </Button>
            </div>
          </div>
        </div>
      )}
    </Modal>
  );
}
