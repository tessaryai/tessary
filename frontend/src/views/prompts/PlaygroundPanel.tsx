// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import type {
  ApiError, CatalogEntry, ModelProvider, PlatformDescriptor, PlaygroundRunRequest, PlaygroundRunView, PromptBody,
  PromptChatMessage, PromptConfig, PromptType, ProviderCredentialView,
} from "../../api/types";
import { usd } from "../../lib/usd";
import { useOrgApi, useTenant } from "../../tenant/TenantContext";
import { Button, Card, EmptyState, ErrorNote, Field, Input, LoadingRow, Select } from "../../ui";
import { Note } from "./promptBits";
import { hasBodyErrors, NO_BODY_ERRORS, PromptBodyEditor, validateBody } from "./PromptBodyEditor";
import { compile, extractPlaceholders, isPlaceholder } from "./promptCompile";
import { usePrompt } from "./promptReads";
import { type VariableValues, VariablesPanel } from "./VariablesPanel";
import { VersionEditorModal } from "./VersionEditorModal";

export type PlaygroundPanelProps = {
  promptId: string;
  version: number;
  type: PromptType;
  body: PromptBody;
  config: PromptConfig;
};

/** Providers with a chat wire the server can reach; Bedrock signs with AWS keys and TypeSafe serves decisions. */
const SUPPORTED = new Set<ModelProvider>(["OPENAI", "ANTHROPIC", "OPENROUTER", "MOONSHOT", "GEMINI", "GLM", "GROK", "CUSTOM"]);
/** Both spellings the server reads; a run and a saved version carry only the snake_case four. */
const FORWARDED = new Set(["temperature", "max_tokens", "maxTokens", "top_p", "topP", "stop"]);
const SOURCE: Record<string, string> = { book: "Price book", openrouter: "OpenRouter" };

type Settings = { temperature: string; maxTokens: string; topP: string; stop: string };

const text = (v: unknown) => (v == null ? "" : String(v));

function settingsOf(config: PromptConfig): Settings {
  const stop = config.stop;
  return {
    temperature: text(config.temperature),
    maxTokens: text(config.max_tokens ?? config.maxTokens),
    topP: text(config.top_p ?? config.topP),
    stop: Array.isArray(stop) ? stop.join(", ") : text(stop),
  };
}

/**
 * Number inputs hold "" or a valid number; the server owns the ranges. Stops go through as stored until the field is
 * edited, because the comma-joined text cannot round-trip a stop that holds a comma or starts with whitespace.
 */
function forwarded(s: Settings, stored: unknown, stopEdited: boolean): PromptConfig {
  const out: PromptConfig = {};
  if (s.temperature) out.temperature = Number(s.temperature);
  if (s.maxTokens) out.max_tokens = Number(s.maxTokens);
  if (s.topP) out.top_p = Number(s.topP);
  if (!stopEdited) {
    if (stored != null) out.stop = stored;
    return out;
  }
  const stop = s.stop.split(",").map((x) => x.trim()).filter(Boolean);
  if (stop.length) out.stop = stop;
  return out;
}

const isMessage = (v: unknown): v is PromptChatMessage =>
  typeof v === "object" && v !== null && typeof (v as PromptChatMessage).role === "string" &&
  typeof (v as PromptChatMessage).content === "string";

/** A placeholder's raw text is a JSON array of {role, content} messages, or it is left out of the run. */
function parseMessages(raw: string): PromptChatMessage[] | null {
  try {
    const value: unknown = JSON.parse(raw);
    if (!Array.isArray(value) || !value.every(isMessage)) return null;
    return value.map((m) => ({ role: m.role, content: m.content }));
  } catch {
    return null;
  }
}

function money(n: number): string {
  return n < 1 ? `$${n.toFixed(4)}` : usd(n);
}

function metaRow(r: PlaygroundRunView): string {
  return [
    r.finish_reason,
    r.input_tokens != null && `${r.input_tokens} in`,
    r.output_tokens != null && `${r.output_tokens} out`,
    `${(r.latency_ms / 1000).toFixed(1)} s`,
    ...(r.cost_usd == null ? ["Unpriced"] : [money(r.cost_usd), SOURCE[r.price_source]]),
  ]
    .filter(Boolean)
    .join(" · ");
}

export function PlaygroundPanel(props: PlaygroundPanelProps) {
  const orgApi = useOrgApi();
  const { orgSlug, projectSlug } = useTenant();
  const providersPath = `/orgs/${orgSlug}/projects/${projectSlug}/settings/providers`;
  const catalog = useQuery({ queryKey: ["provider-catalog", orgApi.base], queryFn: orgApi.listProviderCatalog });
  const credentials = useQuery({ queryKey: ["provider-credentials", orgApi.base], queryFn: orgApi.listProviderCredentials });

  if (catalog.isPending || credentials.isPending) return <LoadingRow label="Loading providers…" />;
  if (catalog.isError || credentials.isError) return <ErrorNote error={catalog.error ?? credentials.error} />;

  const keyed = credentials.data.credentials.filter((c) => c.has_api_key && SUPPORTED.has(c.provider));
  const platforms = catalog.data.platforms.filter((p) => keyed.some((c) => c.provider === p.id));
  if (platforms.length === 0) {
    return (
      <EmptyState
        title="Add a provider key to run the playground"
        body="The playground runs on this organization's own provider keys. Bedrock and TypeSafe keys cannot run it."
        action={<ProvidersLink to={providersPath} />}
      />
    );
  }
  // Keyed so a switch to another version starts from that version's template and config.
  return (
    <Playground
      key={`${props.promptId}:${props.version}`}
      {...props}
      platforms={platforms}
      credentials={keyed}
      models={catalog.data.models}
      providersPath={providersPath}
    />
  );
}

function ProvidersLink({ to }: { to: string }) {
  return (
    <Link to={to} className="text-body text-link hover:text-link-hover">
      Add a provider key
    </Link>
  );
}

function Playground({
  promptId,
  version,
  type,
  body,
  config,
  platforms,
  credentials,
  models,
  providersPath,
}: PlaygroundPanelProps & {
  platforms: PlatformDescriptor[];
  credentials: ProviderCredentialView[];
  models: CatalogEntry[];
  providersPath: string;
}) {
  const api = useTenant().api;
  const prompt = usePrompt(promptId);
  const [edited, setEdited] = useState(body);
  const [bodyErrors, setBodyErrors] = useState(NO_BODY_ERRORS);
  const [values, setValues] = useState<VariableValues>({ vars: {}, placeholders: {} });
  const [settings, setSettings] = useState(() => settingsOf(config));
  const [stopEdited, setStopEdited] = useState(false);
  const [provider, setProvider] = useState<ModelProvider>(platforms[0].id);
  const [pickedModel, setPickedModel] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const choices =
    provider === "CUSTOM"
      ? credentials.filter((c) => c.provider === "CUSTOM").map((c) => ({ value: c.custom_model_name, label: c.custom_model_name }))
      : models.filter((m) => m.provider === provider && !m.decision).map((m) => ({ value: m.model_name, label: m.display_name }));
  const model = pickedModel ?? choices[0]?.value;

  const filled: Record<string, PromptChatMessage[]> = {};
  for (const name of extractPlaceholders(edited)) {
    const messages = parseMessages(values.placeholders[name] ?? "");
    if (messages) filled[name] = messages;
  }
  const unfilled = extractPlaceholders(edited).filter((name) => !filled[name]);
  const notForwarded = Object.keys(config).filter((k) => !FORWARDED.has(k));

  const run = useMutation({ mutationFn: (req: PlaygroundRunRequest) => api.runPlayground(req) });

  const start = () => {
    const errors = validateBody(edited);
    setBodyErrors(errors);
    if (hasBodyErrors(errors)) return;
    const compiled = compile(edited, values.vars, filled);
    const messages =
      typeof compiled === "string"
        ? [{ role: "user", content: compiled }]
        : compiled.flatMap((e) => (isPlaceholder(e) ? [] : [{ role: e.role, content: e.content }]));
    run.mutate({
      provider,
      model: model as string,
      messages,
      config: forwarded(settings, config.stop, stopEdited),
      prompt_ref: { prompt_id: promptId, version },
    });
  };

  const setting = (key: keyof Settings) => (e: { target: { value: string } }) =>
    setSettings((s) => ({ ...s, [key]: e.target.value }));

  return (
    <div className="flex flex-col gap-4">
      <Card className="flex flex-col gap-4 border border-border p-4">
        <div className="grid grid-cols-2 gap-3">
          <Field label="Provider">
            {(p) => (
              <Select
                {...p}
                value={provider}
                onChange={(e) => {
                  setProvider(e.target.value as ModelProvider);
                  setPickedModel(null);
                }}
              >
                {platforms.map((pl) => (
                  <option key={pl.id} value={pl.id}>
                    {pl.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Model">
            {(p) => (
              <Select {...p} value={model ?? ""} disabled={provider === "CUSTOM"} onChange={(e) => setPickedModel(e.target.value)}>
                {choices.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        <div className="flex flex-col gap-2">
          <div className="text-label uppercase text-muted">Template</div>
          <PromptBodyEditor type={type} value={edited} onChange={setEdited} errors={bodyErrors} />
        </div>
        <div className="flex flex-col gap-2">
          <div className="text-label uppercase text-muted">Config</div>
          <div className="grid grid-cols-4 gap-3">
            <Field label="Temperature">
              {(p) => <Input {...p} type="number" step="any" value={settings.temperature} onChange={setting("temperature")} />}
            </Field>
            <Field label="Max tokens">
              {(p) => <Input {...p} type="number" step="1" value={settings.maxTokens} onChange={setting("maxTokens")} />}
            </Field>
            <Field label="Top p">
              {(p) => <Input {...p} type="number" step="any" value={settings.topP} onChange={setting("topP")} />}
            </Field>
            <Field label="Stop sequences" hint="Separate with commas.">
              {(p) => (
                <Input
                  {...p}
                  value={settings.stop}
                  onChange={(e) => {
                    setStopEdited(true);
                    setting("stop")(e);
                  }}
                />
              )}
            </Field>
          </div>
          {notForwarded.length > 0 && (
            <p className="text-small text-subtle">Not forwarded by the playground: {notForwarded.join(", ")}</p>
          )}
        </div>
        <div className="flex flex-col gap-2">
          <div className="text-label uppercase text-muted">Variables</div>
          <VariablesPanel body={edited} values={values} onChange={setValues} />
          {unfilled.map((name) => (
            <Note key={name} tone="warning">
              The run leaves out {name}: fill it with a JSON array of {"{role, content}"} messages.
            </Note>
          ))}
        </div>
        <div className="flex items-center justify-end gap-2">
          <Button onClick={() => setSaving(true)}>Save as new version</Button>
          <Button variant="primary" disabled={!model} loading={run.isPending} onClick={start}>
            Run
          </Button>
        </div>
      </Card>
      {run.isError && (
        <div className="flex flex-col gap-1.5">
          <ErrorNote error={run.error} />
          {(run.error as ApiError).status === 412 && <ProvidersLink to={providersPath} />}
        </div>
      )}
      {run.data && (
        <Card className="flex flex-col gap-3 border border-border p-4">
          <h3 className="text-h3 text-fg">Result</h3>
          <pre className="whitespace-pre-wrap rounded-card border border-border bg-bg p-3 font-mono text-code text-fg-secondary">
            {run.data.content}
          </pre>
          <p className="font-mono text-small text-muted">{metaRow(run.data)}</p>
        </Card>
      )}
      <p className="text-small text-subtle">
        Runs use this organization's provider key and count toward its model spend. They are not recorded as traces and
        do not count in usage.
      </p>
      {prompt.data && (
        <VersionEditorModal
          open={saving}
          promptId={promptId}
          name={prompt.data.name}
          type={type}
          initialBody={edited}
          initialConfig={{
            ...Object.fromEntries(Object.entries(config).filter(([k]) => !FORWARDED.has(k))),
            ...forwarded(settings, config.stop, stopEdited),
          }}
          onClose={() => setSaving(false)}
        />
      )}
    </div>
  );
}
