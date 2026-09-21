// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierField;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector.Kind;
import ai.tessary.classifier.catalog.ClassifierModelModule.Deps;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.detector.DeterministicNlPhraseCompiler;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.detector.MalformedOutputDetector;
import ai.tessary.classifier.detector.RegexDetector;
import ai.tessary.classifier.detector.SecretLeakDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessDetector;
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
 * <p>Nine built-ins ship in four tiers. The <b>deterministic</b> tier costs nothing per observation
 * and calls no model: Secret Leak matches the vendored gitleaks credential corpus ({@link
 * SecretLeakDetector}); Malformed Output validates outputs against the call site's captured schema
 * ({@link MalformedOutputDetector}). The <b>encoder</b> tier is Groundedness, a TOKEN head that reads
 * the retrieved passages and the whole answer in one pass ({@link GroundednessDetector}), scored by
 * the standalone classify-service {@code /classify} with a deterministic filter in front of it. The
 * <b>decision</b> tier is Frustration: each eligible user turn is one question to a hosted decision
 * model on the org's own key, and a call site's rate of frustrated conversations is watched with Tool
 * Error's sequential test. Its detector is not named here by class: it is supplied through the {@link
 * DetectorSupplier} seam rather than built in this file's {@link #MODULES} list, see that module's
 * {@code detectorFactory} comment below. The <b>fitting</b> tier holds five modules that ship as
 * per-project procedures rather than models, and so carry no {@link BuiltInDetector} at all:
 * trace-grain Behaviour Drift ({@code BehaviorDriftDetector}), the two window-grain metric classifiers
 * (Duration Drift and Cost Drift), Tool Errors, and SOP Conformance, scored against an authored
 * rulebook plus a fitted per-project reference bundle. All ship project-local state rather than a
 * model, because "atypical for this agent", "slow for this call site", "expensive for this call site"
 * and "compliant with this SOP" are definitionally project-relative and none has a transferable model
 * to ship.
 *
 * <p>Duration and cost are <b>two switches rather than one or seven</b>. A classifier is one decision
 * a human makes: "do I want to hear about latency here" is a different decision from "do I want to
 * hear about spend", and the measures under each are how that decision is implemented, declared in
 * its {@code defaultConfigJson} rather than as modules of their own. That is what lets one switch span
 * two candidate grains, which a single catalog {@link Grain} cannot express.
 *
 * <p>What every tier has in common is the L1 cost model: <b>no built-in makes a per-observation LLM
 * call.</b> A detector that needs one belongs behind Layer-2 triage, on the findings that already
 * fired, not in front of the whole stream.
 *
 * <p>A classifier withdrawn from the catalog is disabled on every project that has it, never deleted:
 * {@link ClassifierService#resyncBuiltIns}'s retirement path handles that, so history stays listable.
 *
 * <p>Seeding is per-project and idempotent: a project missing a built-in gets it inserted with its
 * module's {@code defaultEnabled}; an existing built-in whose catalog {@code version} advanced has its
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
     * <p>Every built-in seeds enabled except Frustration, which seeds disabled because enabling it
     * spends the org's own provider credit. Trust in an unmeasured classifier is expressed only by who
     * its capability flag is on for, resolved per org without a deploy, rather than by this switch.
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
            String defaultMode,
            /** Whether a freshly seeded row starts enabled; an existing row's switch is never touched. */
            boolean defaultEnabled) {}

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
                    "Frustration the agent caused, judged by TypeSafe's Jev decision model on your "
                            + "OpenRouter or TypeSafe key. Off by default. Scores a user message only when "
                            + "four text messages precede it, and a conversation only until its first "
                            + "detection. Each call site learns its own normal rate of frustrated "
                            + "conversations over its first 200 and is watched from then on with the same "
                            + "sequential test Tool Error uses; a case opens when the rate has risen above "
                            + "that normal. A call site that is bad from day one learns that as normal and "
                            + "is flagged only if it gets worse.",
                    Kind.FRUSTRATION,
                    // ClassifierService re-syncs a built-in onto an already-seeded project only when the
                    // catalog version exceeds the stored one, so every threshold or config change below
                    // needs a bump or it reaches fresh installs only. 9 replaces the encoder config
                    // wholesale; its keys have no reader left.
                    9,
                    Capability.FRUSTRATION,
                    // TURN grain: the subject is what the USER said, and the user says it once. A turn
                    // lands as several observations (agent span + its llm child carrying the same delta +
                    // inner planner/summarizer calls), so scoring per observation would send one user
                    // message several times. The sweep scores that turn's root observation only.
                    Grain.TURN,
                    // Every key here is one FrustrationConfig parses. threshold is the flag cutoff on
                    // P(unhappy_with_assistant), set on a held-out labelled set; it is hashed into the
                    // scorer version, so changing it starts a new set of assessment rows. The rest are the
                    // rate test's dials. arl_target and min_decision_interval are Tool Error's false-alarm
                    // budget converted from tool calls to conversations, reasoned rather than measured.
                    // EXPERIMENT(frustration-tuning): threshold, arl_target and min_decision_interval
                    // are starting values until a null replay on real traffic settles them.
                    "{\"threshold\":0.40,\"arl_target\":10000,\"min_decision_interval\":4,"
                            + "\"shift_multiple\":2.0,\"shift_floor\":0.02,"
                            + "\"min_baseline_conversations\":200}",
                    // null: the detector is JevFrustrationDetector, a Spring bean supplied through the
                    // DetectorSupplier seam (FrustrationDetectorSupplier), because it needs the decision
                    // client, the provider resolver and its own repositories, none of which are Deps.
                    null,
                    // TRACKING: the one band Jev writes is HIGH, so both modes read the same rows; tracking
                    // is kept so a project's stored mode does not change under it.
                    ClassifierRow.Mode.TRACKING,
                    // Seeds disabled: enabling it spends the org's own provider credit, so a person turns
                    // it on, through the enable flow that asks for the key.
                    false),
            new ClassifierModelModule(
                    "secret_leak",
                    "Secret Leak",
                    "The agent's output leaked a credential. Matched against the gitleaks rule set, "
                            + "over 200 credential formats, with no model call. It reads what redaction recorded "
                            + "removing, so a leak is named even after the credential is gone. High confidence "
                            + "is a format the provider stamps into the key, like AKIA or ghp_; a vendor name "
                            + "beside a random string, a JWT, or a bare redaction token is low confidence "
                            + "and never opens a finding.",
                    Kind.SECRET_LEAK,
                    3,
                    Capability.SECRET_LEAK,
                    // Any span can leak a credential: an inner call's output reaches logs and downstream
                    // prompts just like a user-facing one.
                    Grain.OBSERVATION,
                    // Armed at one: a single leaked credential is the whole incident, so there is no
                    // count below which it is noise. ClassifierArming files it per call site and per
                    // pattern. Only the HIGH band counts toward the bar; the classifier stays in
                    // discovery so the LOW band is still listed on its page without opening anything.
                    "{\"arming\":{\"basis\":\"event_count\",\"threshold\":1,\"window_seconds\":86400,"
                            + "\"confidence\":\"high\"}}",
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
                    "The output says something its source content does NOT SUPPORT: either it "
                            + "contradicts the retrieved documents or it asserts what they never say. A "
                            + "long-context token head (ModernBERT-large fine-tuned from the published "
                            + "lettucedetect checkpoint on RAGTruth plus Tessary's own verified corpora) "
                            + "reads every retrieved document and the whole answer in one pass and marks "
                            + "the unsupported words; the score is the strongest unsupported sentence. "
                            + "Measured on human-labelled RAG output: 56% of unsupported sentences caught "
                            + "at a 2% false-alarm rate, equal to the best published model of its size. "
                            + "A true fact taken from a tool call but absent from the retrieved evidence "
                            + "counts as unsupported. Pass tool output in as evidence to ground it. Gated "
                            + "to call sites whose shape declares verifiable source content "
                            + "(extract/summarize/rag_answer); quiet on a turn with no evidence.",
                    Kind.GROUNDEDNESS,
                    // v5 (2026-09-18): the pair head (bart-large-mnli, contradiction-only, per-sentence
                    // windows) is replaced by the token head served as classify-service's `groundedness`
                    // (classify-service/groundedness.js). The contract changed with it — from "contradicts"
                    // to "unsupported: contradicted OR baseless" — because on human-labelled data the
                    // contradiction-only question was unreachable by any model of this size (0.04-0.20
                    // recall at 2% FP, ours and the published ones alike) while the unsupported question is
                    // where the field's own benchmarks sit; see classifiers/groundedness/README.md,
                    // "External validity" and "The long-context token classifier". Evidence reaches the head
                    // as a LIST of documents (GroundingEvidenceReads.Evidence#documents), never one joined
                    // string: the layout with numbered passages is the one the checkpoint was trained on.
                    // A version bump rewrites configJson wholesale, and the band below is NEW, not carried:
                    // the decoded score is now P(unsupported) directly (higher = worse), not 1 - support.
                    // v6 (2026-09-21): the classifier ARMS by default — three detections in a day file a
                    // finding (ClassifierArming) — so Layer 2 has something to rule on; until then no
                    // built-in shipped an arming block and a groundedness detection never left its table.
                    6,
                    // tessaryai/groundedness-token-v1 (MIT; ModernBERT-large, Apache-2.0 base; RAGTruth,
                    // MIT), measured on RAGTruth's human-labelled test split and on Tessary's held-out
                    // corpora before shipping — numbers in the classifiers README and the model card.
                    Capability.GROUNDEDNESS,
                    // Per-call: the pair head scores one output against ITS OWN input, so an inner
                    // retrieval-answer call is exactly as checkable as the outermost one.
                    Grain.OBSERVATION,
                    // Response-level P(unsupported) bands, read off RAGTruth test with thresholds
                    // cross-validated by response: 0.975 is the 2% false-alarm point (recall 0.41,
                    // precision 0.83) — a finding above it is worth a case; 0.50 is the F1-optimal point
                    // (F1 0.66, precision 0.70, recall 0.63) — the band between is "review", where Layer-2
                    // triage earns its keep. Both are the served model's own numbers, not inherited.
                    //
                    // The arming block is the Layer-1 → Layer-2 bar: N detections (either band; the
                    // review band is exactly what triage is for) in a quantised window open or refresh ONE
                    // finding whose evidence is the spans that fired, and that finding is the unit Layer 2
                    // rules on in one microVM. Three in a day is deliberately low: a project that never
                    // reaches it has no groundedness problem worth a machine's look, and one that does
                    // pays for one ruling per window, not one per turn. Owners raise it per project.
                    "{\"threshold_high\":0.975,\"threshold_low\":0.5,"
                            + "\"arming\":{\"basis\":\"event_count\",\"threshold\":3,\"window_seconds\":86400}}",
                    // In-tree since 2026-09-21, when the model (tessaryai/groundedness-token-v1) went
                    // public and the detector moved out of the paid overlay: closed over here like the
                    // other observation-grain detectors, two substrate ports read off the one repository. Until then this
                    // slot was null and the detector arrived through the DetectorSupplier seam below;
                    // that seam stays for detectors that live outside this tree.
                    d -> new GroundednessDetector(d.encoderScorer(), d.substrate(), d.substrate(), d.mapper())),
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
        // kind, or frustration, whose detector is instead supplied through `discovered` below.
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
