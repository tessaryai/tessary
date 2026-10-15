// SPDX-License-Identifier: Apache-2.0
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { useProjectApi } from "../../tenant/TenantContext";
import {
  ApiError,
  type BedrockModelDescriptor,
  type CatalogEntry,
  type ModelLane,
  type LaneProviderOption,
  type ModelLaneGroupView,
  type ModelLaneView,
  type ModelProvider,
  type ModelRateView,
  type ProjectModelSetting,
} from "../../api/types";
import { Button, cn, Modal, PageBody, PageHeader, Select, Skeleton, useToast } from "../../ui";

/**
 * Which model each job runs on. Every section, its copy, each lane's options and their order come
 * from the server, which also enforces them on write; a copy here would drift. With no provider key
 * nothing is selected; Automatic takes the first provider in a lane's order the org's keys satisfy.
 */

/**
 * Not the empty string, which is the "Automatic" option: a native select resolves a duplicated value to
 * the first option. Never sent.
 */
const NO_PROVIDER = "__no_provider__";

/** A native select truncates silently, so it must fit the longest option text. */
const PROVIDER_W = "sm:w-[196px]";
const MODEL_W = "sm:w-[272px]";

/** A Bedrock descriptor carries only `endpoint`, so its `provider` is derived in `models` below. */
type ModelOption = Pick<BedrockModelDescriptor, "model_key" | "display_name"> & {
  provider: ModelProvider;
};

/** `"<PROVIDER>:<model_name>"`, as the server's ModelCatalog#key encodes it. */
function catalogModelKey(entry: CatalogEntry): string {
  return `${entry.provider}:${entry.model_name}`;
}

export function Models() {
  const api = useProjectApi();
  const qc = useQueryClient();
  const toast = useToast();

  const key = ["model-settings", api.base];
  const q = useQuery({ queryKey: key, queryFn: api.getModelSettings });

  const save = useMutation({
    mutationFn: ({ lane, model }: { lane: ModelLane; model: string }) =>
      api.setLaneModel(lane, { model_key: model }),
    onSuccess: (data) => {
      qc.setQueryData(key, data);
      toast.success("Model updated");
    },
    onError: (err) => toast.error("Could not update model", (err as ApiError).message),
  });

  const reset = useMutation({
    mutationFn: (lane: ModelLane) => api.resetLaneModel(lane),
    // Seed from the response: the model it lands on may not be the one it just left.
    onSuccess: (data) => {
      qc.setQueryData(key, data);
      toast.success("Model updated");
    },
    onError: (err) => toast.error("Could not update model", (err as ApiError).message),
  });

  const byLane = useMemo(() => {
    const m = new Map<ModelLane, ProjectModelSetting>();
    for (const s of q.data?.settings ?? []) m.set(s.lane, s);
    return m;
  }, [q.data]);

  const models: ModelOption[] = useMemo(() => {
    const bedrock = (q.data?.models ?? []).map((m) => ({
      ...m,
      provider: (m.endpoint === "MANTLE" ? "BEDROCK_MANTLE" : "BEDROCK") as ModelProvider,
    }));
    const catalog = (q.data?.catalog_models ?? []).map((c) => ({
      model_key: catalogModelKey(c),
      display_name: c.display_name,
      provider: c.provider,
    }));
    return [...bedrock, ...catalog];
  }, [q.data]);
  const rates = q.data?.rates ?? [];
  // A model whose provider the org cannot run is not offered at all.
  const configuredProviders = useMemo(
    () => new Set<ModelProvider>(q.data?.configured_providers ?? []),
    [q.data],
  );

  const sections = useMemo(() => {
    const lanes = q.data?.lanes ?? [];
    return (q.data?.groups ?? [])
      .map((group) => ({ group, lanes: lanes.filter((l) => l.group === group.id) }))
      .filter((s) => s.lanes.length > 0);
  }, [q.data]);

  return (
    <PageBody size="narrow">
      <PageHeader
        eyebrow="Data & ingestion"
        title="Models"
        subtitle="Which model each job runs on. Every job runs on the provider keys this organization has added."
      />

      {q.isLoading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 8 }).map((_, i) => (
            <Skeleton key={i} className="h-[68px] rounded-card" />
          ))}
        </div>
      ) : q.isError ? (
        <p className="text-small text-error">
          {(q.error as ApiError)?.message ?? "Could not load model settings. Try again."}
        </p>
      ) : (
        <>
          {configuredProviders.size === 0 && <NoProviderNotice />}

          <div className="flex flex-col gap-8">
            {sections.map(({ group, lanes }) => (
              <section key={group.id}>
                <h2 className="text-h3 text-fg">{group.label}</h2>
                <p className="mt-1 max-w-prose text-label text-subtle">{group.description}</p>
                <div className="mt-3 flex flex-col gap-2">
                  {lanes.map((lane) => (
                    <LaneRow
                      key={lane.id}
                      lane={lane}
                      group={group}
                      models={models}
                      rates={rates}
                      configuredProviders={configuredProviders}
                      setting={byLane.get(lane.id)}
                      busy={
                        (save.isPending && save.variables?.lane === lane.id) ||
                        (reset.isPending && reset.variables === lane.id)
                      }
                      onChange={(model) => save.mutate({ lane: lane.id, model })}
                      onReset={() => reset.mutate(lane.id)}
                    />
                  ))}
                </div>
              </section>
            ))}
          </div>

          <Footnote />
        </>
      )}
    </PageBody>
  );
}

/** A notice above the lanes, not a replacement: seeing which jobs wait on a key is why to add one. */
function NoProviderNotice() {
  return (
    <div className="mb-8 rounded-card border border-border bg-raised p-4">
      <div className="text-body font-medium text-fg">Add a provider key to choose models</div>
      <p className="mt-1 max-w-prose text-label text-subtle">
        Every job below runs on a key this organization holds, and there are none yet. Add one and each
        job picks the best model that key can serve; you can change any of them afterwards.
      </p>
      <Link
        to="../providers"
        className="mt-3 inline-block text-label text-accent hover:underline"
      >
        Configure providers →
      </Link>
    </div>
  );
}

/**
 * The region is prose because the payload carries none, and trace content that left the region cannot
 * be recalled.
 */
function Footnote() {
  return (
    <p className="mt-8 max-w-prose text-label text-subtle">
      A job left on <em>Automatic</em> takes the first provider in the order above that this
      organization holds a key for, and that provider's default model; picking one pins it until you
      set the job back to Automatic. Triage offers the same models RCA does. A model priced above its
      own provider's default is marked <em>higher cost</em> and asks you to confirm, because triage runs
      unattended once per finding. A decision model lane offers a provider and nothing else,
      because each provider serves one decision model. And note the region: GPT-5.6 Luna is served from{" "}
      <span className="font-mono">us-east-1</span>, which need not be the region the rest of this stack
      runs in, so a lane pointed at it can send that lane's trace content out of your region.
    </p>
  );
}

/**
 * Provider first, model second. Both lists and their order come from the server and are never
 * re-sorted here: the order decides what Automatic picks.
 */
function LaneRow({
  lane,
  group,
  models,
  rates,
  configuredProviders,
  setting,
  busy,
  onChange,
  onReset,
}: {
  lane: ModelLaneView;
  group: ModelLaneGroupView;
  models: ModelOption[];
  rates: ModelRateView[];
  configuredProviders: Set<ModelProvider>;
  setting: ProjectModelSetting | undefined;
  busy: boolean;
  onChange: (model: string) => void;
  onReset: () => void;
}) {
  // `automatic` says whether the selects show the project's own row or the resolved priority order.
  const effectiveKey = lane.effective_model_key ?? null;

  // Never re-ordered: the server's order is the fallback rule in force.
  const providers: LaneProviderOption[] = lane.provider_options.filter((o) =>
    configuredProviders.has(o.provider),
  );
  // Read off the option lists: a Bedrock key carries no provider in its name.
  const effectiveProvider =
    providers.find((o) => o.model_keys.includes(effectiveKey ?? ""))?.provider ?? null;
  const selectedProvider = lane.automatic ? "" : (effectiveProvider ?? "");
  const modelsForProvider = providers.find((o) => o.provider === effectiveProvider)?.model_keys ?? [];

  // A stored choice the org can no longer run falls back to automatic and says so.
  const strandedName =
    lane.automatic && setting
      ? (models.find((m) => m.model_key === setting.model_key)?.display_name ?? setting.model_key)
      : null;

  // Triage only, and only above its provider's default rate; unpriced models never warn. The selects
  // stay bound to what the lane runs, so a cancel needs no revert.
  const [pending, setPending] = useState<{ key: string; step: 1 | 2 } | null>(null);

  const rateFor = (modelKey: string) => rates.find((r) => r.model_key === modelKey);

  const providerOfferingModel = (modelKey: string) =>
    lane.provider_options.find((o) => o.model_keys.includes(modelKey));

  const crossesPriceGate = (modelKey: string) => {
    if (lane.id !== "triage") return false;
    const rate = rateFor(modelKey);
    if (!rate) return false;
    const option = providerOfferingModel(modelKey);
    const defaultRate = option ? rateFor(option.default_model_key) : undefined;
    if (!defaultRate) return false;
    return (
      (rate.input_rate_per_million != null &&
        defaultRate.input_rate_per_million != null &&
        rate.input_rate_per_million > defaultRate.input_rate_per_million) ||
      (rate.output_rate_per_million != null &&
        defaultRate.output_rate_per_million != null &&
        rate.output_rate_per_million > defaultRate.output_rate_per_million)
    );
  };

  const handleModel = (nextKey: string) => {
    const next = models.find((m) => m.model_key === nextKey);
    if (next && !configuredProviders.has(next.provider)) return;
    if (crossesPriceGate(nextKey)) {
      setPending({ key: nextKey, step: 1 });
      return;
    }
    onChange(nextKey);
  };

  // Automatic drops the pin, a delete rather than a write.
  const handleProvider = (nextProvider: string) => {
    if (!nextProvider) {
      onReset();
      return;
    }
    const option = providers.find((o) => o.provider === nextProvider);
    if (option) handleModel(option.default_model_key);
  };

  const pendingModelName = pending
    ? (models.find((m) => m.model_key === pending.key)?.display_name ?? pending.key)
    : "";
  const pendingRate = pending ? rateFor(pending.key) : undefined;
  const pendingDefaultOption = pending ? providerOfferingModel(pending.key) : undefined;
  const pendingDefaultModelName = pendingDefaultOption
    ? (models.find((m) => m.model_key === pendingDefaultOption.default_model_key)?.display_name ??
      pendingDefaultOption.default_model_key)
    : "";
  const pendingDefaultRate = pendingDefaultOption ? rateFor(pendingDefaultOption.default_model_key) : undefined;

  // "No provider configured" only when the org holds no key; keys this lane cannot run need another
  // sentence.
  const noProvider = providers.length === 0;
  const noKeysAtAll = configuredProviders.size === 0;

  return (
    <div className="flex flex-col gap-3 rounded-card border border-border p-4 sm:flex-row sm:items-center">
      <div className="min-w-0 flex-1">
        <div className="text-body font-medium text-fg">{lane.label}</div>
        <div className="mt-0.5 text-label text-subtle">{lane.description}</div>
      </div>

      <div className="flex shrink-0 flex-col gap-1 sm:flex-row sm:items-center">
        <div className={cn("flex flex-col gap-1", PROVIDER_W)}>
          <Select
            aria-label={`${lane.label} provider`}
            className="w-full"
            disabled={busy || noProvider}
            value={noProvider ? NO_PROVIDER : selectedProvider}
            onChange={(e) => handleProvider(e.target.value)}
          >
            {noProvider && (
              <option value={NO_PROVIDER} disabled>
                {noKeysAtAll ? "No provider configured" : "No key runs this job"}
              </option>
            )}
            {!noProvider && (
              <option value="">
                {effectiveProvider
                  ? `Automatic (${providers.find((o) => o.provider === effectiveProvider)?.label})`
                  : "Automatic"}
              </option>
            )}
            {providers.map((o) => (
              <option key={o.provider} value={o.provider}>
                {o.label}
              </option>
            ))}
          </Select>
          {strandedName && !noProvider && (
            <span className="text-label text-subtle">{strandedName} is no longer available</span>
          )}
        </div>

        {group.model_selectable && (
          <div className={cn("flex flex-col gap-1", MODEL_W)}>
            <Select
              aria-label={`${lane.label} model`}
              className="w-full"
              disabled={busy || noProvider || modelsForProvider.length === 0}
              value={effectiveKey ?? NO_PROVIDER}
              onChange={(e) => handleModel(e.target.value)}
            >
              {modelsForProvider.length === 0 && (
                <option value={NO_PROVIDER} disabled>
                  No model
                </option>
              )}
              {modelsForProvider.map((k) => (
                <option key={k} value={k}>
                  {models.find((m) => m.model_key === k)?.display_name ?? k}
                  {crossesPriceGate(k) && " · higher cost"}
                </option>
              ))}
            </Select>
          </div>
        )}
      </div>

      {/* Step 2 is a differently worded dialog so an accidental double-click cannot confirm both. */}
      <Modal
        open={pending?.step === 1}
        onClose={() => setPending(null)}
        title="This model costs more per triage run"
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setPending(null)}>
              Cancel
            </Button>
            <Button variant="primary" onClick={() => setPending((p) => (p ? { ...p, step: 2 } : p))}>
              Continue
            </Button>
          </>
        }
      >
        <p className="text-small text-muted">
          Triage runs on its own, once for each distinct finding, so what this model costs is multiplied
          by how many findings this project produces. {pendingModelName} costs{" "}
          {pendingRate && describeRate(pendingRate)}, more than {pendingDefaultModelName}'s{" "}
          {pendingDefaultRate && describeRate(pendingDefaultRate)}. Every run is billed to this
          organization's provider key.
        </p>
      </Modal>
      <Modal
        open={pending?.step === 2}
        onClose={() => setPending(null)}
        title="Change the triage model?"
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setPending(null)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              loading={busy}
              onClick={() => {
                onChange(pending!.key);
                setPending(null);
              }}
            >
              Set {pendingModelName} on Triage
            </Button>
          </>
        }
      >
        <p className="text-small text-muted">
          Every future triage run uses {pendingModelName} until you change it again. If you are not
          sure it is worth the cost, cancel and keep the current model.
        </p>
      </Modal>
    </div>
  );
}

const RATE_FORMAT = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

function describeRate(rate: ModelRateView): string {
  const { input_rate_per_million: input, output_rate_per_million: output } = rate;
  return [
    input != null && `${RATE_FORMAT.format(input)} per million input tokens`,
    output != null && `${RATE_FORMAT.format(output)} per million output tokens`,
  ]
    .filter((p): p is string => typeof p === "string")
    .join(" and ");
}
