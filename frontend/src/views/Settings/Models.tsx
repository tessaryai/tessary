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
  type ServiceTier,
} from "../../api/types";
import { effortLabel } from "../../lib/effort";
import { Button, cn, Modal, PageBody, PageHeader, Select, Skeleton, useToast } from "../../ui";

/**
 * The price-gated warning threshold for the triage lane only: a project must be told, twice, before
 * it points the lane that runs once per distinct cause (routinely dozens of times a day) at a
 * frontier-priced model. Strict `>`, not `>=`: Haiku 4.5's $1/$5 rate must not warn, since Haiku is
 * priced low enough to be a reasonable triage choice. Luna and Haiku both clear this bar; Sonnet 5
 * and Terra do not.
 */
const TRIAGE_WARN_INPUT_PER_MILLION = 1;
const TRIAGE_WARN_OUTPUT_PER_MILLION = 5;

/**
 * Settings, Models: which model each job runs on.
 *
 * Sibling of Providers, and deliberately distinct from it: Providers is "which keys this org has
 * stored", this is "which model each of our jobs spends them on". No lane runs on a platform
 * credential; every model here bills the org.
 *
 * That is also why the order between the two pages is the product: a key is what makes any model
 * reachable, so this page has nothing to offer until Providers has something in it. With no key it
 * says so and picks nothing. With one key each job takes the first provider in its own order that
 * the key satisfies, and that provider's default model, and keeps taking it, so adding a
 * higher-priority provider later moves every job nobody has pinned by hand.
 *
 * The page holds no knowledge of its own about lanes or models. Sections, the copy under each
 * heading, which controls a section shows, each lane's options and their order all arrive in the
 * payload, because every one of them is a product decision the server also has to enforce on write:
 * a copy of any of them here would be a second answer that drifts and cannot be validated.
 *
 * The one split worth understanding while reading this file is the section split: an "LLM calls" lane
 * is a request we compose and send, so a service tier and a reasoning effort are ours to choose, while
 * an "Agent in a VM" lane hands a model id to an agent inside a sandbox that composes its own
 * requests. That is why the second section is a model dropdown and nothing else.
 */

/**
 * Human copy for a tier. The wire values are lowercase; these are what a person reads. Kept to a
 * short qualifier rather than a full sentence: the trade still reads at the point of choice, but
 * the option fits its select, and the note under the list carries the detail.
 */
const TIER_LABEL: Record<ServiceTier, string> = {
  standard: "Standard",
  flex: "Flex · half price",
  priority: "Priority · faster",
  // Never rendered: batch has no online form, so the server never lists it as supported. Present
  // only so this map stays total over the type: if a tier is ever added, tsc points here.
  batch: "Batch",
};

/**
 * Value of the placeholder a lane selects when the org has no provider key at all. A sentinel rather
 * than the empty string, because the empty string is the "Automatic" option and a native select
 * resolves a duplicated value to whichever option comes first. Never sent: the option carrying it is
 * disabled, and the change handlers refuse it anyway.
 */
const NO_PROVIDER = "__no_provider__";

/**
 * Control widths, shared by the selects and by the placeholder that stands in for a row with no tier
 * or no effort: as one constant each, because the whole point is that they line up down a section.
 * Wide enough for the longest option text ("Custom (OpenAI-compatible)", "GPT-5.6 Luna (OpenAI)",
 * "Priority · faster") with room for a longer name later: a native select truncates silently, so a
 * too-narrow one hides exactly the thing being chosen.
 */
const PROVIDER_W = "sm:w-[196px]";
const MODEL_W = "sm:w-[224px]";
const TIER_W = "sm:w-[168px]";
// Narrower than the others: the longest option is "Extra high", and reasoning effort is the least
// consequential of the three choices, so it should not be the widest thing on the row.
const EFFORT_W = "sm:w-[132px]";

/**
 * What a row and the footnote actually read off a model, regardless of which of the two source
 * types it came from. A {@link BedrockModelDescriptor} satisfies this structurally as-is; a
 * {@link CatalogEntry} (GEMINI/GLM/GROK/CUSTOM) is mapped into it below, see `models` in
 * {@link Models}. Narrower than either source type on purpose: `supported_tiers` is the one field a
 * catalog entry has no equivalent for, and it is always empty on a mapped one, which is safe because
 * every lane a catalog key can appear on is non-tiered (`group.tiered` is false for
 * {@link ModelLaneGroupView} `AGENT_VM`), so `tiers` in {@link LaneRow} is never read off it.
 *
 * `provider` is what {@link LaneRow} checks against `configured_providers` to decide whether an
 * option is reachable at all. A {@link BedrockModelDescriptor} carries no `provider` field of its
 * own, only `endpoint` (`"RUNTIME" | "MANTLE"`), so it is derived below in {@link Models}'s `models`
 * memo.
 */
type ModelOption = Pick<BedrockModelDescriptor, "model_key" | "display_name" | "supported_tiers" | "effort_levels"> & {
  provider: ModelProvider;
};

/**
 * A {@link CatalogEntry}'s wire key, as {@code ModelCatalog#key} on the server encodes it and
 * {@code ProjectModelSettings}'s {@code model_key} union decodes it:
 * {@code "<PROVIDER>:<model_name>"}. Kept as one function so the client and server never spell the
 * encoding two different ways.
 */
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
    mutationFn: ({
      lane,
      model,
      tier,
      effort,
    }: {
      lane: ModelLane;
      model: string;
      tier: ServiceTier;
      effort: string | null;
    }) => api.setLaneModel(lane, { model_key: model, service_tier: tier, reasoning_effort: effort }),
    // The PUT returns the whole refreshed view, so seed the cache with it rather than refetching.
    onSuccess: (data) => {
      qc.setQueryData(key, data);
      toast.success("Model updated");
    },
    onError: (err) => toast.error("Could not update model", (err as ApiError).message),
  });

  const reset = useMutation({
    mutationFn: (lane: ModelLane) => api.resetLaneModel(lane),
    // Going back to automatic reads as "chose a model" like any other option, because it is one: the
    // lane immediately runs whatever the priority order resolves to. Seed the cache from the response
    // for the same reason the save does: the model it lands on may not be the one it just left.
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

  // The settings page's model list is the union BedrockModelProfile ∪ ModelCatalog: a
  // GEMINI/GLM/GROK/CUSTOM entry mapped into the same shape a Bedrock one already renders as, keyed
  // the way an AGENT_VM lane's own `model_keys` names it (see catalogModelKey).
  const models: ModelOption[] = useMemo(() => {
    const bedrock = (q.data?.models ?? []).map((m) => ({
      ...m,
      // MANTLE serves the GPT-5.6 line over bedrock-mantle; every other endpoint value is Bedrock
      // Converse proper. See ModelOption's own javadoc for why this is derived rather than sent.
      provider: (m.endpoint === "MANTLE" ? "BEDROCK_MANTLE" : "BEDROCK") as ModelProvider,
    }));
    const catalog = (q.data?.catalog_models ?? []).map((c) => ({
      model_key: catalogModelKey(c),
      display_name: c.display_name,
      supported_tiers: [] as ServiceTier[],
      effort_levels: c.effort_levels,
      provider: c.provider,
    }));
    return [...bedrock, ...catalog];
  }, [q.data]);
  const rates = q.data?.rates ?? [];
  // The providers this org has a credential for. A model whose provider is absent is not offered at
  // all: an option nobody can pick is noise, and naming the key it would need turns a settings page
  // into a shopping list: one that is wrong as often as not, since the same model is reachable
  // through more than one provider.
  const configuredProviders = useMemo(
    () => new Set<ModelProvider>(q.data?.configured_providers ?? []),
    [q.data],
  );

  // Sections in the server's order, each holding its own lanes in the server's order. A group with no
  // lanes is dropped rather than drawn as an empty heading: it cannot happen today, but a heading
  // over nothing is a worse failure than a missing section.
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
                      onChange={(model, tier, effort) =>
                        save.mutate({ lane: lane.id, model, tier, effort })
                      }
                      onReset={() => reset.mutate(lane.id)}
                    />
                  ))}
                </div>
              </section>
            ))}
          </div>

          <Footnote models={models} />
        </>
      )}
    </PageBody>
  );
}

/**
 * The state this page has to say out loud rather than paper over: no provider key, so no model can
 * run, so nothing below is selected. It is a notice above the lanes rather than a replacement for
 * them, because seeing which jobs are waiting on a key is the reason to go and add one.
 */
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
 * The things about this page that a control cannot say for itself.
 *
 * Which models offer Flex is read off the catalogue rather than written here, because that is exactly
 * what the payload knows and a hand-written model name would go stale the moment the line-up changed.
 * The region sentence is prose because the payload carries no region: it is a fact about where the
 * two Bedrock endpoints are deployed, and it is the one thing on this page a reader cannot undo
 * later, since trace content that left the region has left it.
 */
function Footnote({ models }: { models: ModelOption[] }) {
  const flex = models.filter((m) => m.supported_tiers.includes("flex")).map((m) => m.display_name);

  return (
    <p className="mt-8 max-w-prose text-label text-subtle">
      A job left on <em>Automatic</em> takes the first provider in the order above that this
      organization holds a key for, and that provider's default model; picking one pins it until you
      set the job back to Automatic. Triage offers only models at or under $1 per million input tokens
      and $5 per million output, because it runs unattended once per cause.{" "}
      {flex.length > 0 && (
        <>Flex costs about half of Standard for work that can wait; it is offered by {flex.join(", ")}. </>
      )}
      Reasoning effort trades answer depth against tokens, on the models that declare levels for it.
      Both controls belong to requests Tessary composes, so neither appears on a lane whose model is
      driven by an agent inside a sandbox. And note the region: GPT-5.6 Luna is served from{" "}
      <span className="font-mono">us-east-1</span>, which need not be the region the rest of this stack
      runs in, so a lane pointed at it can send that lane's trace content out of your region.
    </p>
  );
}

/**
 * One job's row: a provider, a model on it, and, for a lane whose request we build, the tier and
 * effort to run it at.
 *
 * <p>Provider first, model second, because that is the order the choice actually has: a key is the
 * thing an org either holds or does not, and which model to run is a question inside it. The provider
 * select offers Automatic plus every provider this org has a key for; the model select offers that
 * provider's own models for this lane. Both lists, and their order, come from the server (see
 * `LanePriority`), and this component never re-sorts them, because the order is the rule that decides
 * what Automatic picks.
 *
 * <p>Two decisions are the server's and only rendered here: which models a lane may use (a small
 * model can drive a sandbox agent and still not be something we run over a whole repository, and the
 * triage lane refuses anything above $1/$5 per million tokens outright), and which one wins when
 * nobody has chosen. The tier list is the selected model's own `supported_tiers`, which is what makes
 * an impossible pair (Flex on a Standard-only model) unpickable rather than a failure that surfaces
 * hours later on the next run.
 *
 * <p>Both the tier and the effort control are omitted rather than shown inert when there is nothing
 * to choose: the group does not take them, or the selected model offers a single tier / no effort
 * levels. A disabled select still reads as a setting.
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
  /** Providers the org has a credential for: gates which options exist at all. */
  configuredProviders: Set<ModelProvider>;
  setting: ProjectModelSetting | undefined;
  busy: boolean;
  onChange: (model: string, tier: ServiceTier, effort: string | null) => void;
  onReset: () => void;
}) {
  // The selects show what the lane runs, which is not always what the project stored. `automatic`
  // says which of the two it is: the project's own row, or the priority order resolved against the
  // org's keys. Automatic is the empty value on the provider select, so the two never collide.
  const effectiveKey = lane.effective_model_key ?? null;
  const effectiveModel = models.find((m) => m.model_key === effectiveKey);
  const tier: ServiceTier = setting?.service_tier ?? "standard";
  // Empty string is the meaningful value here as well as on the wire: it means "send no reasoning
  // parameter", which is the only thing a model with no effort control can do.
  const effort = lane.automatic ? "" : (setting?.reasoning_effort ?? "");

  // Only the providers this org can actually run. Never re-ordered: the server's order is the rule
  // that decides what Automatic resolves to, so showing a different one would describe a different
  // fallback than the one in force.
  const providers: LaneProviderOption[] = lane.provider_options.filter((o) =>
    configuredProviders.has(o.provider),
  );
  // Which provider the running model belongs to. Read off the option lists rather than off the key's
  // own prefix, because a Bedrock key carries no provider in its name at all.
  const effectiveProvider =
    providers.find((o) => o.model_keys.includes(effectiveKey ?? ""))?.provider ?? null;
  const selectedProvider = lane.automatic ? "" : (effectiveProvider ?? "");
  const modelsForProvider = providers.find((o) => o.provider === effectiveProvider)?.model_keys ?? [];

  // A stored choice the org can no longer run: its provider's key was removed, or the model left
  // this lane's offer list (which triage's price ceiling can do on its own). The lane falls back to
  // automatic rather than breaking, and says so: silently ignoring a choice someone made is how a
  // settings page starts lying about itself.
  const strandedName =
    lane.automatic && setting
      ? (models.find((m) => m.model_key === setting.model_key)?.display_name ?? setting.model_key)
      : null;

  const tiers = group.tiered ? (effectiveModel?.supported_tiers ?? []) : [];
  const efforts = group.effort_tunable ? (effectiveModel?.effort_levels ?? []) : [];

  // Triage only, and only above the threshold (see TRIAGE_WARN_*). The lane's offer list is curated
  // under that same threshold, so this cannot fire on a fresh checkout: it is the backstop for the
  // price book moving under a static list, which is why it reads live rates instead of hardcoding
  // them. `pending` holds the (model, tier, effort) the two-step dialog is confirming; the selects
  // stay bound to what the lane runs throughout, so a cancel at either step needs no explicit revert.
  const [pending, setPending] = useState<{
    key: string;
    tier: ServiceTier;
    effort: string | null;
    step: 1 | 2;
  } | null>(null);

  const crossesPriceGate = (modelKey: string) => {
    if (lane.id !== "triage") return false;
    const rate = rates.find((r) => r.model_key === modelKey);
    if (!rate) return false;
    return (
      (rate.input_rate_per_million ?? 0) > TRIAGE_WARN_INPUT_PER_MILLION ||
      (rate.output_rate_per_million ?? 0) > TRIAGE_WARN_OUTPUT_PER_MILLION
    );
  };

  // Changing model can strip the current tier (Flex → a Standard-only model) or the current effort (a
  // mantle model's `max` → a Converse model that takes none). Fall back rather than sending a
  // combination the server would reject.
  const handleModel = (nextKey: string) => {
    if (!nextKey || nextKey === NO_PROVIDER) return;
    const next = models.find((m) => m.model_key === nextKey);
    if (next && !configuredProviders.has(next.provider)) return;
    const keepsTier = next?.supported_tiers.includes(tier) ?? false;
    const keepsEffort = !!effort && (next?.effort_levels.includes(effort) ?? false);
    const nextTier = keepsTier ? tier : "standard";
    const nextEffort = keepsEffort ? effort : null;
    if (crossesPriceGate(nextKey)) {
      setPending({ key: nextKey, tier: nextTier, effort: nextEffort, step: 1 });
      return;
    }
    onChange(nextKey, nextTier, nextEffort);
  };

  // Choosing a provider pins that provider's own default model. Choosing Automatic drops the pin
  // entirely, which is a delete rather than a write: there is no default row to put back.
  const handleProvider = (nextProvider: string) => {
    if (nextProvider === NO_PROVIDER) return;
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

  // Two different states, and each needs its own sentence. "No provider configured" is true only
  // when the org holds no key at all; when it holds keys that this lane cannot run, saying the same
  // thing tells someone looking straight at their own configured providers that they have none.
  // Every provider currently reaches every lane (see LanePriority's coverage rule), so the second
  // state is unreachable today, but the distinction stays ready for whenever a future provider
  // doesn't.
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
            {/* Nothing this org holds a key for can run this lane. Disabled: it is a thing to be, not
                a thing to choose, and the way out of it is Providers. */}
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
              </option>
            ))}
          </Select>
        </div>

        {/* A tier is only a choice where there is more than one of them. The others hold the column so
            the rows still line up down the section. */}
        {group.tiered &&
          (tiers.length < 2 ? (
            <div className={cn("hidden sm:block", TIER_W)} aria-hidden />
          ) : (
            <Select
              aria-label={`${lane.label} service tier`}
              className={cn("w-full", TIER_W)}
              disabled={busy || !effectiveKey}
              value={tier}
              onChange={(e) => onChange(effectiveKey ?? "", e.target.value as ServiceTier, effort || null)}
            >
              {tiers.map((t) => (
                <option key={t} value={t}>
                  {TIER_LABEL[t]}
                </option>
              ))}
            </Select>
          ))}

        {/* Same treatment for effort: only the models that declare levels get the control. */}
        {group.effort_tunable &&
          (efforts.length === 0 ? (
            <div className={cn("hidden sm:block", EFFORT_W)} aria-hidden />
          ) : (
            <Select
              aria-label={`${lane.label} reasoning effort`}
              className={cn("w-full", EFFORT_W)}
              disabled={busy || !effectiveKey}
              value={effort}
              onChange={(e) => onChange(effectiveKey ?? "", tier, e.target.value || null)}
            >
              {/* The model's own default: distinct from every named level. */}
              <option value="">Default effort</option>
              {efforts.map((level) => (
                <option key={level} value={level}>
                  {effortLabel(level)}
                </option>
              ))}
            </Select>
          ))}
      </div>

      {/* The price-gated warning, two confirmations deep. Step 1 states the trade in plain terms;
          step 2 makes the click cost something rather than doubling as an accidental double-click
          on step 1's own button: a second, differently-worded dialog reads as a real pause rather
          than the same confirm answered twice. */}
      <Modal
        open={pending?.step === 1}
        onClose={() => setPending(null)}
        title="This model costs significantly more"
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
          Triage runs once per finding, routinely many times a day. {pendingModelName} costs more than
          $1 per million input tokens or $5 per million output tokens, and every run is billed to this
          project.
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
                if (!pending) return;
                onChange(pending.key, pending.tier, pending.effort);
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
