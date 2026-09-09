// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierField;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.DeterministicNlPhraseCompiler;
import ai.tessary.classifier.detector.EncoderDetector;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.MalformedOutputDetector;
import ai.tessary.classifier.detector.RegexDetector;
import ai.tessary.classifier.detector.SecretLeakDetector;
import ai.tessary.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.plan.Capability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The starter pack of built-in classifiers, declared as {@link ClassifierModelModule} manifests, one
 * comprehensive declaration per classifier. The catalog derives its {@link BuiltIn} list and its
 * detector-dispatch map from {@link #MODULES}, so adding or changing a classifier is a single
 * declaration here, not edits scattered across the catalog, the detector list, and seeding.
 *
 * <p>Nine built-ins ship in three tiers. The <b>deterministic</b> tier costs nothing per observation
 * and calls no model: Secret Leak matches a curated credential-pattern set ({@link
 * SecretLeakDetector}); Malformed Output validates outputs against the call site's captured schema
 * ({@link MalformedOutputDetector}). The <b>encoder</b> tier scores observation text against a shared
 * ONNX head served by the standalone classify-service {@code /classify}: Frustration ({@link
 * EncoderDetector}) and Groundedness, the one PAIR head, a claim against a premise rather than one
 * string in isolation, and the one detector with a deterministic filter in front of it. Groundedness's
 * detector is not named here by class: it is supplied through the {@link DetectorSupplier} seam
 * rather than built in this file's {@link #MODULES} list, see that module's {@code detectorFactory}
 * comment below. The <b>fitting</b> tier holds five modules that ship as per-project procedures
 * rather than models, and so carry no {@link BuiltInDetector} at all: trace-grain Behaviour Drift
 * ({@code BehaviorDriftDetector}), the two window-grain metric classifiers (Duration Drift and Cost
 * Drift), Tool Errors, and SOP Conformance, scored against an authored rulebook plus a fitted
 * per-project reference bundle. All ship project-local state rather than a model, because "atypical
 * for this agent", "slow for this call site", "expensive for this call site" and "compliant with this
 * SOP" are definitionally project-relative and none has a transferable model to ship.
 *
 * <p>Duration and cost are <b>two switches rather than one or seven</b>. A classifier is one decision
 * a human makes: "do I want to hear about latency here" is a different decision from "do I want to
 * hear about spend", and the measures under each are how that decision is implemented, declared in
 * its {@code defaultConfigJson} rather than as modules of their own. That is what lets one switch span
 * two candidate grains, which a single catalog {@link Grain} cannot express.
 *
 * <p>What every tier has in common is the L1 cost model: <b>no built-in makes a per-observation API
 * call.</b> A detector that needs one belongs behind Layer-2 triage, on the findings that already
 * fired, not in front of the whole stream.
 *
 * <p>A classifier withdrawn from the catalog is disabled on every project that has it, never deleted:
 * {@link ClassifierService#resyncBuiltIns}'s retirement path handles that, so history stays listable.
 *
 * <p>Seeding is per-project and idempotent: a project missing a built-in gets it inserted with
 * {@code enabled=true}; an existing built-in whose catalog {@code version} advanced has its
 * definition re-synced. User enable/disable state is never clobbered.
 *
 * <p><b>Catalog membership is not availability.</b> Every module here is defined for every org,
 * always; whether one reaches a given org is its {@link ClassifierModelModule#capability()}, resolved
 * per org by the flag layer. Keep the two apart: {@link #builtIns()} is membership and must never be
 * filtered by a flag, and seeding filters a copy of it. Deleting a flag-gated module from
 * {@link #MODULES} to "turn it off" would disable it in every project including the orgs whose flag
 * says on, and the rows would never come back.
 *
 * <p>The flag-off ending is <b>suppression, not a write</b>: a withheld built-in's row is hidden from
 * every read and skipped by the sweep enqueue while its {@code enabled} column keeps whatever the
 * project set ({@code ClassifierService#withheldBuiltInKeys}). A module removed from this list is
 * gone for everyone and irreversible; a module whose capability is off is fully restored the moment
 * the flag flips back, which is why "turn it off" always means the flag and never a deletion here.
 */
@Component
public class BuiltInClassifierCatalog {

    /**
     * A built-in signal definition: catalog metadata plus the detector kind the worker dispatches
     * on. {@code capability} rides along because seeding reads it, to decide whether the classifier
     * reaches the org at all.
     *
     * <p>Every built-in seeds enabled; trust in an unmeasured classifier is expressed only by who its
     * capability flag is on for, resolved per org without a deploy, rather than by a second switch here.
     */
    public record BuiltIn(
            String classifierKey,
            String name,
            String description,
            String detector,
            @Nullable String defaultConfigJson,
            int version,
            Capability capability,
            /**
             * The operating point a FRESHLY-SEEDED row takes. Not applied to an already-seeded
             * project: {@code ClassifierService} preserves the tenant's stored mode across a
             * re-seed, so moving an existing estate is a migration, deliberately and once.
             */
            String defaultMode) {}

    /**
     * The classifier manifests, the single source of truth for the built-in catalog. Each entry
     * declares one classifier's metadata, operating point, and detector factory in one place.
     * Detector factories are pure (they only close over the {@link Deps} passed at build time), so
     * this list is static; the catalog binds it to the real dependencies in its constructor.
     */
    static final List<ClassifierModelModule> MODULES = List.of(
            new ClassifierModelModule(
                    "frustration",
                    "Frustration",
                    // User-facing, and re-synced onto every seeded project by the version bump below, so
                    // it has to track the scorer: a stale description here gets written into production
                    // rows as fact.
                    "Frustration the AGENT caused. Two heads: cirimus ModernBERT-GoEmotions scores the "
                            + "last exchange for emotion (calibrated max(annoyance, anger)), and a second "
                            + "head reads the full thread and judges whether the agent's own conduct caused "
                            + "it. A turn is HIGH only if BOTH agree; real frustration aimed at the "
                            + "restaurant, the courier, a promo code or a billing bug is demoted to LOW "
                            + "rather than dropped, so discovery mode still shows it. A conversation's "
                            + "opening turn is not scored (nothing the agent did could have caused it). "
                            + "Behavioral/task-failure frustration is a separate signal.",
                    Kind.FRUSTRATION,
                    // ClassifierService re-syncs a built-in onto an already-seeded project only when the
                    // catalog version exceeds the stored one, so every threshold or config change below
                    // needs a bump or it reaches fresh installs only. Ship the classify-service scorer
                    // change before a band change that assumes it: a backend on a tighter band against the
                    // old scorer under-fires, which is the safe direction, the reverse over-fires.
                    8,
                    Capability.FRUSTRATION,
                    // TURN grain: the subject is what the USER said, and the user says it once. A turn
                    // lands as several observations (agent span + its llm child carrying the same delta +
                    // inner planner/summarizer calls), so scoring per observation would draw the head's
                    // calibrated per-item false-positive rate several times over ONE user message and
                    // emit several verdicts for it. The sweep scores that turn's root observation only.
                    Grain.TURN,
                    // EMOTION member: cirimus (28-label GoEmotions) emotion proxy, calibrated in classify.js.
                    // Band 0.66/0.90 is the measured F1 peak against real production traffic
                    // (annoyance/anger only, with the turn gate below); 0.90 is a genuine confidence tier
                    // rather than a flat-precision cutoff.
                    //
                    // GATE: skip a conversation's opener (context_min_prior_user_turns=1). The agent has
                    // not acted yet, so any emotion there is what the user arrived with, not something the
                    // product caused; this costs a few true positives for a real precision gain.
                    //
                    // CONTEXT: the last exchange only, assistant prose stubbed to "[reply]". cirimus is a
                    // pooled single-utterance head with no way to weight the trailing turn, so more context
                    // dilutes the message being judged; one exchange scores short turns far better than an
                    // unbounded thread does.
                    //
                    // NOTE: this catches EMOTIONAL frustration only; emotion-less task-failure/loops are a
                    // separate signal, unioned with this at the signal layer.
                    //
                    // ATTRIBUTION GATE: a HIGH emotion score is re-scored by the `attribution` head over
                    // the full thread and demoted to LOW below 0.64. The emotion band alone is
                    // mis-specified rather than miscalibrated: on a hand-labelled census only a fifth of
                    // its HIGH fires were frustration the agent actually caused, and no threshold on the
                    // emotion score alone fixes that.
                    //
                    // 0.64, not the head's own 0.81 cutoff, because of a train/serve mismatch: the head
                    // was trained on a template with a [SEP] separator that the real thread renderer never
                    // emits, which shifts its scores enough to move the calibrated threshold. Recall is
                    // deliberately traded for precision here, since this signal over-fires; the knob is
                    // per-project config, so a project that wants recall can lower or remove it.
                    "{\"threshold_high\":0.90,\"threshold_low\":0.66,"
                            + "\"context_user_turns\":1,\"context_stub_assistant\":true,"
                            + "\"context_min_prior_user_turns\":1,"
                            + "\"attribution_head\":\"attribution\",\"attribution_threshold\":0.64}",
                    // INPUT selects the scored user turn; the CONTEXT is the narrowed thread described
                    // above, so "nevermind" is legible against the assistant reply it reacts to.
                    d -> new EncoderDetector(
                            Kind.FRUSTRATION,
                            "frustration",
                            ClassifierField.INPUT,
                            Detection.Severity.WARN,
                            d.encoderScorer(),
                            d.mapper(),
                            d.threadAssembler()),
                    // TRACKING, not the catalog's discovery default: the high+low union fires too often
                    // to be a review queue, so every project is seeded at the volume it actually operates
                    // at rather than gated behind a graduation step nothing ever triggers.
                    ClassifierRow.Mode.TRACKING),
            new ClassifierModelModule(
                    "secret_leak",
                    "Secret Leak",
                    "The agent's output leaked a credential — a curated pattern set (cloud keys, "
                            + "VCS/chat tokens, private-key blocks, JWTs, assigned secrets with an entropy "
                            + "gate) matched over the output with no model call.",
                    Kind.SECRET_LEAK,
                    2,
                    Capability.SECRET_LEAK,
                    // Any span can leak a credential: an inner call's output reaches logs and downstream
                    // prompts just like a user-facing one.
                    Grain.OBSERVATION,
                    null,
                    d -> new SecretLeakDetector(d.mapper())),
            new ClassifierModelModule(
                    "malformed_output",
                    "Malformed Output",
                    "The output violates the call site's declared structured-output schema — not "
                            + "JSON, or JSON that fails the captured JSON Schema. Deterministic; quiet "
                            + "until generation captures a schema for the call site.",
                    Kind.MALFORMED_OUTPUT,
                    1,
                    Capability.MALFORMED_OUTPUT,
                    // Per-call by definition: each call site's own output is validated against its own
                    // declared schema, inner calls included.
                    Grain.OBSERVATION,
                    null,
                    d -> new MalformedOutputDetector(d.substrate(), d.mapper())),
            new ClassifierModelModule(
                    "groundedness",
                    "Groundedness",
                    "The output CONTRADICTS its source content — a three-way NLI head "
                            + "(bart-large-mnli) scores each asserted sentence against the source the "
                            + "trace actually produced: the retrieved documents where there are any, the "
                            + "prompt where the document sits in the prompt. It fires on contradiction "
                            + "only. A sentence the source simply does not mention is NOT a finding — "
                            + "most such sentences are facts the agent got from a tool, and calling them "
                            + "hallucinations was this classifier's largest error. The cost of that is "
                            + "stated plainly: an INVENTED addition the source is silent on reads the "
                            + "same as a true one and is not caught. Gated to call sites whose shape "
                            + "declares verifiable source content (extract/summarize/rag_answer), quiet "
                            + "on a turn that asserts nothing checkable, and tool-backed answers remain "
                            + "out of scope.",
                    Kind.GROUNDEDNESS,
                    // The premise is the trace's own evidence (retrieved documents, or the prompt where
                    // the document sits in it), scored per sentence rather than per answer, and an answer
                    // with no verifiable sentence abstains. Tool results are withdrawn as an evidence
                    // carrier entirely: a claim sourced from a tool call abstains rather than firing,
                    // because the head has no reliable way to judge it against a policy document that
                    // could neither confirm nor deny it. bart-large-mnli is three-way, so it can express
                    // that abstain (NEUTRAL) where a binary support/not-support head cannot. A version
                    // bump here rewrites configJson wholesale, so an operator's edited thresholds are
                    // replaced by the catalog's, which is why the values below are unchanged from v1:
                    // the decoded `unsupported` score (1 - P(contradiction)) is sharply bimodal, so the
                    // same 0.9/0.6 band still sits in empty space.
                    4,
                    // bart-large-mnli (MIT), three-way MNLI, off-the-shelf but measured against this
                    // classifier's own labelled data before shipping.
                    Capability.GROUNDEDNESS,
                    // Per-call: the pair head scores one output against ITS OWN input, so an inner
                    // retrieval-answer call is exactly as checkable as the outermost one.
                    Grain.OBSERVATION,
                    // Unsupportedness (1 - support) bands, see GroundednessDetector's threshold_high/
                    // threshold_low commentary. Wide separation observed in spot checks (supported
                    // ~0.95+, unsupported/contradicted ~0.01-0.10); revisit once the eval harness has
                    // measured this head's actual recall@fixed-fp.
                    "{\"threshold_high\":0.9,\"threshold_low\":0.6}",
                    // null, not a factory lambda: the one detectorFactory here that is null for a reason
                    // other than "not observation/turn grain" (see ClassifierModelModule's
                    // DetectorFactory javadoc). Groundedness is observation-grain, but its detector is
                    // supplied externally through the DetectorSupplier seam folded into this class's
                    // constructor below, rather than closed over here by class reference, so this file
                    // never has to name that implementation directly. The manifest entry still owns every
                    // other fact about the classifier, catalog metadata, the operating point, the
                    // capability and grain, because check-classifier-quality-doc.sh greps this config
                    // literal by literal path.
                    null),
            new ClassifierModelModule(
                    "behavior_drift",
                    "Behaviour Drift",
                    "The agent is doing something it does not usually do — atypical action sequences, "
                            + "new capabilities appearing, established steps quietly disappearing. Ships as "
                            + "a fitting procedure, not a model: it learns this project's normal from this "
                            + "project's own traces, with no labels, and stays silent until the learned "
                            + "baseline saturates.",
                    Kind.BEHAVIOR_DRIFT,
                    1,
                    // No measured operating point yet: there is no portable gold set for "atypical for
                    // this agent" and there cannot be one, so the eval is synthetic injection (recall at
                    // a fixed alert budget, per perturbation operator) and the gate is set from the first
                    // measured run rather than guessed here.
                    Capability.BEHAVIOR_DRIFT,
                    // Trace grain: an action skeleton only exists across a whole trace.
                    Grain.TRACE,
                    // The BehaviorDriftConfig policy defaults, stated explicitly so a project can move
                    // its own operating point without a redeploy.
                    "{\"alert_budget_per_1k\":3,\"min_support\":30,\"graduation_sessions\":50,"
                            + "\"graduation_span_days\":3,\"omission_support\":0.9,\"rare_floor\":0.001,"
                            + "\"max_order\":3,\"arm_min_traces\":300,\"arm_discovery_floor\":2.0,"
                            + "\"arm_sustained_fits\":2,\"novelty_count_floor\":10,"
                            + "\"trace_settle_seconds\":300}",
                    null),
            new ClassifierModelModule(
                    "duration_drift",
                    "Duration Drift",
                    // User-facing, and re-synced onto every seeded project by the version bump below, so
                    // it names both grains now that both are measured. "This call site got slower" and
                    // "this tool inside it got slower" are the same question asked at two depths, and an
                    // operator reading only the first sentence would not know the second was covered.
                    "This call site's turns — and the individual tool calls inside them — are taking "
                            + "measurably longer, or shorter, than they recently did. Compares each "
                            + "population's own recent distribution against its own earlier one, so nobody "
                            + "has to know what \"slow\" means for a call site they have never read: the "
                            + "bar is that population's own past. A turn that is slow because one tool is "
                            + "slow reports once, naming the tool. Labels no individual trace, because a "
                            + "forty-second research run and a two-second lookup are both routinely "
                            + "correct.",
                    Kind.DURATION_DRIFT,
                    // A version bump rewrites configJson wholesale on every already-seeded project, so an
                    // operator's edited thresholds are replaced by the catalog's each time. That costs
                    // little while the numbers below are unmeasured; once a null-case run lands a tuned
                    // operating point, a bump starts costing something. A key with no reader left in
                    // MetricDriftConfig is dropped in the same bump that orphans it, rather than sitting in
                    // a project's config reading as a dial that does nothing.
                    4,
                    // Seeds enabled, like every built-in; whether this one reaches anyone but us is
                    // `duration_drift_enabled`, and while its numbers are unvalidated (see w1_floor below)
                    // that flag is the only thing standing between the guess and a partner's Triage.
                    Capability.DURATION_DRIFT,
                    // WINDOW grain: the scored unit is a stretch of one bucket's traffic summarized as a
                    // distribution. See ClassifierModelModule.Grain#WINDOW for why that is the honest
                    // answer rather than a workaround for grainFor() returning one grain per detector.
                    Grain.WINDOW,
                    // The measures this switch governs, declared HERE rather than as separate modules:
                    // "this turn was slow" and "one tool call inside it was slow" are two halves of one
                    // question and therefore one on/off switch across two candidate grains, which a single
                    // catalog Grain cannot express. MetricDriftConfig carries the per-measure grain and
                    // MetricDriftSweep reads it from there.
                    //
                    // tool_duration ships with the suppression rule and never without it: a tool finding
                    // that does not suppress the turn it explains reports one cause twice, in a stream that
                    // has no alert budget to absorb the duplicate. explained_by_fraction is that rule's one
                    // dial, how much of a turn's added time one tool's own added time has to account for.
                    // A turn shift nothing explains still fires alone, which is invisible at tool grain and
                    // the whole reason turn duration is measured too.
                    //
                    // w1_floor (0.139, roughly 1.15x) is set from a synthetic null run rather than real
                    // traffic; a real null-case run is what settles it, and until that has happened this
                    // classifier belongs to orgs whose flag we set deliberately.
                    "{\"measures\":[\"turn_duration\",\"tool_duration\"],\"window_target_count\":500,"
                            + "\"window_max_hours\":24,\"min_sample\":100,\"w1_floor\":0.139,"
                            + "\"explained_by_fraction\":0.5,\"settle_seconds\":300,\"hist_bins\":320}",
                    // No detector factory, exactly as behaviour drift has none: this is a per-project
                    // fitting procedure dispatched through the ClassifierSweep registered for this kind on
                    // ClassifierWorker's Grain.WINDOW branch, not an observation-grain BuiltInDetector.
                    //
                    // callSiteFactsRead() is deliberately empty, worth saying since there's no object here
                    // to say it on. It exists for detectors gated on a call_site column captured from the
                    // repository, where a fact landing late must rewind the sweep cursor of the signals
                    // that declare it. This classifier reads observation columns and the trace spine only,
                    // both present from the very first trace, so nothing outside the trace can invalidate
                    // a sweep of it and the declared set is genuinely empty.
                    null),
            new ClassifierModelModule(
                    "cost_drift",
                    "Cost Drift",
                    // USER-FACING. It names the decomposition because that is what distinguishes this
                    // classifier from a spend chart: an operator who reads only "cost moved" would go and
                    // read spend by model, which is the work this is supposed to have already done.
                    "This call site's turns are costing measurably more, or less, than they recently "
                            + "did. Compares each call site's own recent cost distribution against its own "
                            + "earlier one, so nobody has to know what \"expensive\" means for a call site "
                            + "they have never read. Every finding carries what the dollars were made of "
                            + "— input, output, cache-read and cache-write tokens, and the share of the "
                            + "prompt served from cache — so a prompt edit that quietly stopped the cache "
                            + "hitting reads as one finding that names itself rather than five that do "
                            + "not. Labels no individual trace, because a $0.40 research turn and a "
                            + "$0.004 lookup are both routinely correct.",
                    Kind.COST_DRIFT,
                    // Kept in step with duration drift's blob so an operator reading one is not surprised
                    // by the other.
                    3,
                    // Seeds enabled and is held back only by its flag, exactly as duration drift is, and for
                    // the same reason: w1_floor below is simulated and no real null case has run yet.
                    Capability.COST_DRIFT,
                    // WINDOW grain, dispatched through this kind's registered ClassifierSweep. The scored
                    // unit is a stretch of one call site's traffic summarized as a distribution of dollars
                    // per turn.
                    Grain.WINDOW,
                    // `cost` is the only measure listed on purpose: MetricDriftConfig's registry holds
                    // specs only for measures that can open a finding, so the four token buckets can't be
                    // turned into findings by editing this blob. They're still computed every window by
                    // MetricSource and printed inside the cost finding's evidence as the decomposition
                    // that explains it.
                    //
                    // settle_seconds matters here and not for duration_drift: cost sums over a trace's
                    // spans, so it has to wait for every span to arrive, or measuring early would read as
                    // cheap and surface as a permanent drift toward cheaper whenever ingest lags. Duration
                    // is read off a single span whose arrival is its own completion signal, so it needs no
                    // such wait.
                    //
                    // w1_floor is the same simulated 0.139 as duration drift, unvalidated for the same
                    // reason.
                    "{\"measures\":[\"cost\"],\"window_target_count\":500,"
                            + "\"window_max_hours\":24,\"min_sample\":100,\"w1_floor\":0.139,"
                            + "\"explained_by_fraction\":0.5,\"settle_seconds\":300,\"hist_bins\":320}",
                    // No detector factory, and callSiteFactsRead() deliberately empty, both exactly as for
                    // duration drift above: this reads observation columns (usage, model, call_site_id)
                    // and the trace spine, nothing captured onto `call_site` from a repository. Its
                    // late-arriving-fact hazard is the price book, and the rewind seam is the wrong tool
                    // for it, since rewinding rebuilds a current window but never a pinned reference. The
                    // mitigation is to resolve rates at sweep time instead, which MetricSource does.
                    null),
            new ClassifierModelModule(
                    "tool_error",
                    "Tool Errors",
                    // USER-FACING. It names the grouping, because that is what separates this from a chart
                    // of failure counts: an operator told only "errors are up" goes and reads a list of
                    // failing calls, which is the work this is supposed to have already done for them.
                    "This tool is failing more often — or less often — than it recently did. Watches each "
                            + "tool's failure rate against that tool's own past, so nobody has to know what "
                            + "\"normal\" is for a tool they have never called, and groups the failures by "
                            + "the pattern of the error so a finding says WHICH failure took over rather "
                            + "than only that the number moved. Counts a call as failed on the span status, "
                            + "on an OTel error attribute, or on a result that declares itself an error — "
                            + "including the common case where the tool caught the error, handed it back to "
                            + "the model, and closed the span cleanly.",
                    Kind.TOOL_ERROR,
                    1,
                    Capability.TOOL_ERROR,
                    // WINDOW: the scored unit is a stretch of one tool's traffic, tested against that
                    // tool's own earlier stretch. Its own sweep drives it from the worker like every other
                    // classifier, with the WINDOW branch looking this detector kind up in
                    // ClassifierSweepRegistry rather than falling into metric drift.
                    Grain.WINDOW,
                    // Every key here is one ToolErrorConfig parses. decision_interval is the CUSUM
                    // threshold and it is a guess: 6.0 buys a false alarm about every 250,000 calls under
                    // independent Bernoulli trials, and real tool failures are bursty in a way that
                    // arithmetic cannot price; a null run against a real corpus is what replaces it, and it
                    // will very likely move up.
                    //
                    // min_effect_size is not a second threshold on the same thing, it is the guard that
                    // makes a sequential test usable at volume: a CUSUM accumulates evidence indefinitely,
                    // so on a busy tool it eventually crosses on a tenth of a percentage point, which is
                    // real and is nobody's problem.
                    "{\"decision_interval\":6.0,\"shift_multiple\":2.0,\"shift_floor\":0.005,"
                            + "\"min_effect_size\":0.05,\"min_baseline_calls\":500,"
                            + "\"down_arm_min_rate\":0.01,\"settle_seconds\":300,\"max_patterns\":8}",
                    // No detector factory and no sweep. callSiteFactsRead() is moot for the same reason it
                    // is empty on the metric modules: this reads tool_call, the observation attribute bag
                    // and the trace spine, nothing captured onto call_site from a repository.
                    null),
            new ClassifierModelModule(
                    "sop_conformance",
                    "SOP Conformance",
                    // User-facing. It says both halves, per-turn conformance and windowed drift,
                    // because "did the agent follow the SOP on this turn" and "did compliance fall
                    // below what history predicts" are the two questions the classifier answers, and
                    // an operator reading only one would mis-file the findings it raises.
                    "The agent is drifting from a written SOP — for every authored rule, each turn is "
                            + "judged (did the rule apply, was it satisfied), and each rule's compliance "
                            + "rate is tested against what the reference period predicts for this traffic "
                            + "(one-sided, Bonferroni, minimum effect). Turns whose intent the reference "
                            + "never saw stay out of the test's evidence, and a rule whose activation "
                            + "gate itself shifted carries a \"reference stale — refit the gate\" "
                            + "annotation instead of a silent verdict; a second, distinct gate annotation "
                            + "(\"gate precision degraded (PACC)\") marks windows where the gate is "
                            + "admitting turns the rule does not apply to even inside intents the "
                            + "reference knew — same remediation, refit the gate, but the deficit likely "
                            + "belongs to falsely-admitted turns rather than the agent. Requires an "
                            + "authored SOP and a "
                            + "fitted artifact bundle from the conformance engine. Interim serving "
                            + "posture: turn-text embedding runs in-process on the backend, bounded to a "
                            + "configured number of concurrent encoder passes (the classify-service "
                            + "exists because of the 2026-07-12 OOM incident; in-JVM was chosen so the "
                            + "fit arithmetic stays parity-pinnable) — the accepted plan moves embedding "
                            + "behind a classify-service /embed endpoint before any project is enabled.",
                    Kind.SOP_CONFORMANCE,
                    // Description and config here re-sync onto an already-seeded project only when this
                    // version advances (ClassifierService.seedBuiltIns), so a description or config-key
                    // change needs a bump or an existing project keeps the stale text or an absent key
                    // reading as unset. shadow_mode is a case in point: a project seeded before it existed
                    // would otherwise read the key as absent and default to surfacing findings rather than
                    // staying quiet.
                    4,
                    // Seeds enabled, like every built-in, and is held back by its flag alone, which is
                    // targeted on for no org yet: the engine's zero-false-alarm figure was measured on one
                    // corpus with synthetic injected drift, and no per-project bundle exists until an
                    // operator deploys one (ConformanceArtifactStore). Behind the flag, an
                    // enabled-but-bundle-less row's sweep is a cursor-preserving no-op, which is the second
                    // fence.
                    Capability.SOP_CONFORMANCE,
                    // WINDOW grain: the drift test fires on a window of a rule's admitted activations,
                    // never on one turn; a per-turn verdict row is evidence, not a finding. An enabled
                    // row rides ClassifierWorker's WINDOW branch, which resolves this kind to its own
                    // registered sweep, one lookup, no ordering between classifiers to get wrong.
                    Grain.WINDOW,
                    // measures stays [] as belt-and-braces: dispatch resolves this kind to its own sweep,
                    // but if that routing ever regresses, an empty list keeps the metric-drift fold inert
                    // (an absent list falls back to the duration measures, which would open duration
                    // findings under the conformance switch). alpha / min_activations / min_effect are the
                    // engine's frozen drift knobs (Bonferroni across the SOP's rules, windows of at least
                    // 30 activations, 0.10 minimum effect), parsed by conformance.ConformanceConfig.
                    // settle_seconds and drift_window_turns are serving knobs with no engine counterpart,
                    // since the engine scores a finished file while the sweep scores a live stream: how
                    // old a turn's trace must be before it is scored, and how many stored per-turn
                    // verdicts one rule's windowed drift test reads. shadow_mode is the third, and the
                    // only one about who sees the output rather than how it is computed: true keeps
                    // scoring and recording while withholding automatic escalation and case opening
                    // (ConformanceShadowMode). Seeded false, so a project opts into shadow rather than a
                    // detector ever being muted by an absent key.
                    "{\"measures\":[],\"alpha\":0.01,\"min_activations\":30,\"min_effect\":0.1,"
                            + "\"settle_seconds\":300,\"drift_window_turns\":2000,\"shadow_mode\":false}",
                    // No detector factory, exactly as the other fitting-tier classifiers: nothing
                    // observation-grain to dispatch. The ClassifierSweep registered for this kind owns the
                    // dispatch, feeding the scoring service with the phase-A repositories and the encoder.
                    null));

    private final List<BuiltIn> builtIns;
    private final Map<String, BuiltInDetector> detectors;
    private final Map<String, Grain> grains;

    public BuiltInClassifierCatalog(
            ObjectMapper mapper,
            SubstrateReadRepository substrate,
            EncoderScorer encoderScorer,
            ConversationThreadAssembler threadAssembler,
            ObjectProvider<DetectorSupplier> discovered) {
        Deps deps = new Deps(mapper, encoderScorer, substrate, threadAssembler);

        // Catalog metadata + built-in detectors are both derived from the manifests.
        this.builtIns = MODULES.stream().map(ClassifierModelModule::toBuiltIn).toList();

        // Sweep grain per detector kind, also derived from the manifests. Only kinds declared by a
        // module appear here; the dispatch-only detector below (user regex) scores
        // one span at a time and falls through to the OBSERVATION default in grainFor.
        this.grains = MODULES.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ClassifierModelModule::detectorKind, ClassifierModelModule::grain));

        // One dispatch-only detector is NOT a catalog built-in but is registered so the worker
        // dispatches its kind for USER-authored signals:
        //  - RegexDetector backs USER-authored `regex` classifiers; empty default phrases (each such row
        //    supplies its own via config_json), ClassifierField.BOTH so a phrase can match either side of
        //    the exchange, WARN because a user tracker feeds metrics, not incident escalation. Nothing
        //    mints one of these any more, but the kind stays dispatched so rows already on disk keep
        //    scoring.
        BuiltInDetector regex = new RegexDetector(
                Kind.REGEX,
                ClassifierField.BOTH,
                Detection.Severity.WARN,
                List.of(),
                true,
                new DeterministicNlPhraseCompiler(),
                mapper);

        // toUnmodifiableMap fails loud on a duplicate detector kind (IllegalStateException), the
        // fail-loud invariant kept via the collector framework rather than an explicit constructor
        // throw, which SpotBugs forbids (CT_CONSTRUCTOR_THROW). A module with no factory is one of
        // the five fitting-tier classifiers, dispatched by the ClassifierSweep registered for their
        // kind, or groundedness, whose detector is instead supplied through `discovered` below.
        //
        // `discovered` is the generic source: any DetectorSupplier bean on the classpath is folded in
        // for the kind it claims, with no check against MODULES membership; see DetectorSupplier's
        // class comment for the failure mode that trades for. ObjectProvider, not
        // List<DetectorSupplier>, because Spring treats a required List with no candidate bean as an
        // unsatisfied dependency, and nothing discovered must be an ordinary empty answer, not a boot
        // failure.
        this.detectors = Stream.concat(
                        Stream.concat(
                                MODULES.stream()
                                        .map(ClassifierModelModule::detectorFactory)
                                        .filter(java.util.Objects::nonNull)
                                        .map(factory -> factory.build(deps)),
                                Stream.of(regex)),
                        discovered.orderedStream().map(supplier -> supplier.build(deps)))
                .collect(Collectors.toUnmodifiableMap(BuiltInDetector::kind, Function.identity()));
    }

    public List<BuiltIn> builtIns() {
        return builtIns;
    }

    /** The detector for a {@code signal.detector} value, or {@code null} for inert/unknown kinds. */
    @Nullable
    public BuiltInDetector detectorFor(String detectorKind) {
        return detectors.get(detectorKind);
    }

    /**
     * The sweep {@link Grain} a {@code signal.detector} value scores at: what one scored unit is
     * for that classifier. Defaults to {@link Grain#OBSERVATION} for anything not declared by a
     * module (user-authored regex/classifiers, and inert or unknown kinds), since a classifier is
     * per-span unless it says otherwise.
     */
    public Grain grainFor(String detectorKind) {
        return grains.getOrDefault(detectorKind, Grain.OBSERVATION);
    }
}
