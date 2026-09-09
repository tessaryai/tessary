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
 * The starter pack of built-in classifiers, declared as {@link ClassifierModelModule} manifests — one
 * comprehensive declaration per classifier. The catalog derives its {@link BuiltIn} list and its
 * detector-dispatch map from {@link #MODULES}, so adding or changing a classifier is a single
 * declaration here, not edits scattered across the catalog, the detector list, and seeding.
 *
 * <p>Nine built-ins ship in three tiers. The <b>deterministic</b> tier costs nothing per observation
 * and calls no model: Secret Leak matches a curated credential-pattern set ({@link
 * SecretLeakDetector}); Malformed Output validates outputs against the call site's captured schema
 * ({@link MalformedOutputDetector}). The <b>encoder</b> tier scores observation text against a shared
 * ONNX head served by the standalone classify-service {@code /classify}: Frustration ({@link
 * EncoderDetector}) and Groundedness, the one PAIR head — a claim against a premise, not one string in
 * isolation, and the one detector with a deterministic filter in front of it. Groundedness's detector is
 * not named here by class: it is supplied from {@code tessary-paid/groundedness} through the {@link
 * DetectorSupplier} seam rather than built in this file's {@link #MODULES} list — see that module's
 * {@code detectorFactory} comment below. The <b>fitting</b> tier holds five modules that ship as
 * per-project procedures rather than models, and so carry no {@link BuiltInDetector} at all:
 * trace-grain Behaviour Drift ({@code BehaviorDriftDetector}), and the two window-grain metric
 * classifiers — Duration Drift and Cost Drift. Tool Errors joins them and is the odd one out: its sweep
 * accumulates nothing, because a failure rate is re-derivable from an hourly aggregate and is therefore
 * recomputed on every pass rather than carried forward
 * ({@code classifiers/tool_error/PROGRAM.md} §5). SOP Conformance completes the tier: window-grain like
 * the metric pair, but scored against an AUTHORED rulebook plus a fitted per-project reference bundle.
 * All four sweeps reach the worker through the {@code ClassifierSweep} seam registered for their kind,
 * and this class names none of them on purpose: the catalog declares what the platform DEFINES, and
 * which implementation is on the classpath is a separate question with a separate answer per edition.
 * All five ship project-local state rather than a model, because "atypical for this
 * agent", "slow for this call site", "expensive for this call site" and "compliant with this SOP" are
 * definitionally project-relative and none has a transferable model to ship.
 *
 * <p>Duration and cost are <b>two switches rather than one or seven</b> (metric drift's PROGRAM.md
 * §3.1). A classifier is one decision a human makes — "do I want to hear about latency here" is a
 * different decision from "do I want to hear about spend" — and the measures under each are how that
 * decision is implemented, declared in its {@code defaultConfigJson} rather than as modules of their
 * own. That is what lets one switch span two candidate grains, which a single catalog {@link Grain}
 * cannot express.
 *
 * <p>What every tier has in common is the L1 cost model: <b>no built-in makes a per-observation API
 * call.</b> A detector that needs one belongs behind Layer-2 triage, on the findings that
 * already fired, not in front of the whole stream.
 *
 * <p><b>2026-07-16 catalog reduction:</b> refusal, jailbreak, jailbreak_success, and unsafe_text
 * (style/safety encoder heads) and task_failure (the boolean predecessor of the richer Tool Error
 * built-in) were dropped from the catalog — kept classifiers earn their keep by catching what the
 * others structurally can't (Groundedness is the output-vs-source correctness check none of the
 * dropped style/safety heads provide). Existing projects self-heal via {@link
 * ClassifierService#resyncBuiltIns}'s retirement path — a dropped built-in's row is disabled (never
 * deleted, history stays listable), not migrated. Their classify-service heads (refusal/jailbreak/
 * unsafe_text) were decommissioned in the same change — see classify-service/classify.js.
 *
 * <p>Seeding is per-project + idempotent (mirrors {@code SystemDefaultSeeder}'s posture): a project
 * missing a built-in gets it inserted with {@code enabled=true}; an existing built-in whose catalog
 * {@code version} advanced has its definition re-synced. User enable/disable state is never clobbered.
 *
 * <p><b>Catalog membership is not availability.</b> Every module here is defined for every org, always;
 * whether one reaches a given org is its {@link ClassifierModelModule#capability()}, resolved per org by the
 * flag layer. The distinction is load-bearing rather than pedantic, because {@code
 * ClassifierService#resyncBuiltIns}' retirement path disables a seeded row whose key has LEFT this list — the
 * ending a withdrawn classifier deserves, and exactly the wrong ending for one that is merely flagged off for
 * this org. Keep the two apart: {@link #builtIns()} is membership and must never be filtered by a flag, and
 * seeding filters a COPY of it. Deleting a flag-gated module from {@link #MODULES} to "turn it off" would
 * disable it in every project including the orgs whose flag says on, and the rows would never come back.
 *
 * <p>The flag-off ending is <b>suppression, not a write</b>: a withheld built-in's row is hidden from every
 * read and skipped by the sweep enqueue while its {@code enabled} column keeps whatever the project set
 * ({@code ClassifierService#withheldBuiltInKeys}). So a module removed from this list is gone for everyone
 * and irreversible, whereas a module whose capability is off is fully restored the moment the flag flips
 * back — which is precisely why "turn it off" always means the flag and never a deletion here.
 */
@Component
public class BuiltInClassifierCatalog {

    /**
     * A built-in signal definition: catalog metadata + the detector kind the worker dispatches on.
     * {@code capability} rides along because seeding reads it, to decide whether the classifier reaches the
     * org at all.
     *
     * <p><b>There is no seeds-enabled derivation any more.</b> A module used to carry a {@code lifecycle},
     * and a classifier marked EXPERIMENTAL seeded switched off so an unmeasured operating point could not
     * start firing on its own. That was a second switch expressing the same intent as the first: a
     * classifier we do not trust yet is one we do not hand to anyone but ourselves, which the capability
     * flag already says, per org, from a console, without a deploy. Every built-in now seeds enabled and
     * trust is expressed only by who the flag is on for.
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
     * The classifier manifests — the single source of truth for the built-in catalog. Each entry
     * declares one classifier's metadata, operating point, and detector factory in one place. Detector factories are pure (they only close over the {@link Deps} passed at build
     * time), so this list is static; the catalog binds it to the real dependencies in its
     * constructor.
     */
    static final List<ClassifierModelModule> MODULES = List.of(
            new ClassifierModelModule(
                    "frustration",
                    "Frustration",
                    // This string is USER-FACING and is re-synced onto every seeded project by the version
                    // bump below, so it has to track the scorer: it named `disappointment` until that label
                    // was dropped from the proxy in this PR, and a stale description here would have been
                    // written into production rows as fact.
                    "Frustration the AGENT caused. Two heads: cirimus ModernBERT-GoEmotions scores the "
                            + "last exchange for emotion (calibrated max(annoyance, anger)), and a second "
                            + "head reads the full thread and judges whether the agent's own conduct caused "
                            + "it. A turn is HIGH only if BOTH agree; real frustration aimed at the "
                            + "restaurant, the courier, a promo code or a billing bug is demoted to LOW "
                            + "rather than dropped, so discovery mode still shows it. A conversation's "
                            + "opening turn is not scored (nothing the agent did could have caused it). "
                            + "Behavioral/task-failure frustration is a separate signal.",
                    Kind.FRUSTRATION,
                    // 7: the band moved again (0.67/0.85 -> 0.66/0.90) and the opener gate is new. Both live
                    // in defaultConfigJson, and ClassifierService re-syncs a built-in onto an ALREADY-SEEDED
                    // project only when the catalog version exceeds the stored one — so without this bump
                    // every existing project keeps the previous config forever and the change reaches nothing
                    // but fresh installs. Same reason #625 bumped 3->4, #627 4->5, #639 5->6.
                    //
                    // The classify.js scorer changed in the same PR (disappointment dropped from the proxy),
                    // which this version does NOT gate — the service is deployed separately. Ship the
                    // classify-service image FIRST: a backend on band 0.66 against the old three-label
                    // scorer under-fires, which is the safe direction, whereas the reverse over-fires.
                    // 8: the ATTRIBUTION GATE is switched on (attribution_head/attribution_threshold
                    // below), and the description above changed with it because HIGH now means a
                    // different thing — emotion AND agent-attribution, not emotion alone. Both live in
                    // defaultConfigJson/description, and ClassifierService re-syncs a built-in onto an
                    // ALREADY-SEEDED project only when the catalog version exceeds the stored one, so
                    // without this bump the gate would reach fresh installs only and every existing
                    // project would keep firing ungated. Same reason #625 bumped 3->4, #627 4->5,
                    // #639 5->6, #802 6->7.
                    8,
                    Capability.FRUSTRATION,
                    // TURN grain: the subject is what the USER said, and the user says it once. A turn
                    // lands as several observations (agent span + its llm child carrying the same delta +
                    // inner planner/summarizer calls), so scoring per observation would draw the head's
                    // calibrated per-item false-positive rate several times over ONE user message and
                    // emit several verdicts for it. The sweep scores that turn's root observation only.
                    Grain.TURN,
                    // EMOTION member: cirimus (28-label GoEmotions) emotion proxy, CALIBRATED in classify.js
                    // (Platt fit on the agentic register). The 0.4/0.6 band that fit came with was set from
                    // that synthetic register, which is ~51% positive; real traffic is ~19%, and the prior
                    // shift put the WARN line BELOW neutral text ("Please confirm the cashless hospital
                    // list" scored 0.579). Measured against 300 human-labelled production turns it fired on
                    // 46% of them at precision 0.30.
                    //
                    // Re-derived on that labelled set for the CURRENT scorer (annoyance/anger, no
                    // disappointment): 0.66 is the F1 peak (P 0.66 / R 0.68 / F1 0.672) with the turn gate
                    // below. threshold_high is 0.90 and is now a GENUINE confidence tier, unlike the
                    // previous band where precision was flat: it rises 0.66 -> 0.85 across this range,
                    // because the label that was flat (disappointment) is gone. Refit both the calibration
                    // (w,b) and this band on more labelled traffic; 300 turns labelled largely by one
                    // annotator is the current evidence base.
                    //
                    // GATE: skip a conversation's OPENER (context_min_prior_user_turns=1). The agent has
                    // not acted yet, so any emotion there is what the user arrived with, not something the
                    // product caused — and the labels agree: 4% of turn-0 messages are frustrated vs 30% at
                    // turn 2. Costs 3 of 57 true positives, lifts precision 0.47 -> 0.58 on its own. The
                    // count is taken on the UNNARROWED thread, so context_user_turns cannot move this gate.
                    //
                    // CONTEXT: the last exchange only, assistant prose stubbed to "[reply]". cirimus is a
                    // pooled single-utterance head — one score for the whole string, no way to weight the
                    // trailing turn — so prepended blocks dilute the message being judged. AUC over the same
                    // 300 turns: 0.838 bare, 0.830 at one exchange, 0.805 at two, 0.801 at three, 0.718
                    // unbounded (today's shape). One exchange ties bare overall while scoring SHORT turns far
                    // better (AUC 0.773 vs 0.557) — "ok fine, noted the ticket number" reads as a reaction
                    // only against the reply it answers, which is the whole reason a thread is sent at all.
                    // NOTE: this catches EMOTIONAL frustration only; emotion-less task-failure/loops are the
                    // separate `task_failure` classifier, unioned with this at the signal layer.
                    //
                    // ATTRIBUTION GATE: a HIGH emotion score is re-scored by the `attribution` head over
                    // the FULL thread, and demoted to LOW below 0.81. The emotion band alone is
                    // mis-specified rather than miscalibrated — on an 884-turn hand-labelled census only
                    // 21% of its HIGH fires are frustration the agent caused, and no threshold on it
                    // fixes that, because within HIGH it separates causation at chance (AUC 0.541). It
                    // cannot reach 40% precision at ANY threshold; it fires on nothing first.
                    //
                    // 0.64 is the precision-first operating point, measured on the RESIDUAL population
                    // (turns whose context carries a tool error are excluded — those belong to the
                    // tool-error classifier, and this signal is meant to own the case where the agent
                    // did nothing wrong and the user is still stuck):
                    //
                    //     incumbent, emotion @ HIGH   163 fires  precision 0.196  recall 0.444
                    //     gated at 0.64                16 fires  precision 0.500  recall 0.111
                    //
                    // 0.64 AND NOT 0.81 because of a train/serve string difference caught in review.
                    // The head was trained and evaluated on `{context}\n[SEP]\n[user] {turn}`, but
                    // ConversationThreadRenderer emits no [SEP] — the assembled thread is what the
                    // service actually sends. Re-scored on that exact string the calibration moves:
                    // the same 0.500 precision sits at 0.64, and the old 0.81 would have fired 11
                    // times at recall 0.083, roughly half the intended coverage. Absent [SEP] shifts
                    // scores by 0.071 on average and flips 46 of 884 decisions, so this is a real
                    // difference and not rounding. The durable fix is template parity between
                    // training and the renderer; until then the threshold is calibrated on what
                    // serving sends, which is the half that governs behaviour.
                    //
                    // The recall loss is deliberate: this signal over-fires, so recall bought at the
                    // cost of precision is worth nothing. It is also the weakest number here — it rests
                    // on 13 true positives, a [0.316-0.750] interval, and a gold set whose annotator
                    // self-agreement measured kappa 0.529, which is wider than the interval. Re-measure
                    // against a second annotator before treating 0.81 as settled; the knob is per-project
                    // config, so a project that wants recall can lower or remove it.
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
                    // TRACKING, not the catalog's discovery default. Frustration's discovery phase is
                    // over and its answer was measured in production (2026-08-20): the high+low union
                    // surfaced 67.4% of interview-coach turns, 28.5% of zipeats, 20.5% of policy-gpt,
                    // against 18.6/14.6/3.5% for the HIGH band alone. Two turns in three is not a
                    // review queue. Every project was seeded wide because graduating a signal was a
                    // manual per-signal flag nothing ever called, so the default is now the bar we
                    // actually operate at and widening is the deliberate act.
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
                    // Any span can leak a credential — an inner call's output reaches logs and downstream
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
                    // 2: three changes reach behaviour, none of them through config. The premise is now
                    // the trace's evidence rather than the prompt (retrieved docs were joined on the LLM
                    // observation, which never owns them, so RAG scored against the QUESTION and fired on
                    // 100% of answers — 60/60 correct after the fix). Scoring is per sentence, not per
                    // answer. And an answer with no verifiable sentence abstains: of 54 measured turns
                    // with no evidence the old shape fired on all 54, 48 of them greetings and questions.
                    // The version carries the DESCRIPTION onto already-seeded projects. Note that a
                    // bump rewrites configJson wholesale, so an operator's edited thresholds are
                    // replaced by the catalog's — pre-existing resync behaviour, called out here
                    // because the values below are deliberately IDENTICAL to v1's for that reason.
                    //
                    // 3: tool results withdrawn as an evidence carrier, which withdraws tool-backed
                    // turns from the classifier's scope entirely (they abstain). 84 of 84 measured
                    // firings on that path were false. The description says so, because an operator
                    // reading "the output isn't supported by its source" would otherwise reasonably
                    // assume their tool-calling agent was covered.
                    //
                    // 4: the head changes, and with it what a finding MEANS. MiniCheck-RoBERTa-Large is
                    // BINARY — trained to answer "is this claim supported, yes or no" — so "the document
                    // does not mention it" collapsed into NO. Enabling this classifier on zipeats fired
                    // 474 times at ~28% of swept observations, every one an answer asserting a fact from
                    // a tool call against a policy document that could neither confirm nor deny it.
                    // Measured on 40 labelled claims, MiniCheck scored those TRUE tool-derived facts at
                    // 0.006 support, BELOW outright fabrications at 0.064: no threshold separates them,
                    // so no calibration could have fixed it. bart-large-mnli is three-way, and NEUTRAL is
                    // the abstain the binary head had no way to express. Same 40 claims: precision 1.000
                    // (0 false fires across 20 should-be-quiet claims), recall 0.500. The lost recall is
                    // fabricated ADDITIONS, input-identical to true tool-derived facts; MiniCheck only
                    // appeared to catch those because it fired on everything but a verbatim restatement.
                    //
                    // Thresholds below are UNCHANGED, deliberately: with the decode returning
                    // 1 - P(contradiction), the detector's `unsupported` becomes P(contradiction), whose
                    // distribution is sharply bimodal (contradicted median 0.998, silent max 0.129), so
                    // 0.9/0.6 sit in empty space. Measured, not assumed.
                    4,
                    // bart-large-mnli (MIT), three-way MNLI. Off-the-shelf, but no longer unmeasured:
                    // see the 40-claim numbers above and tessary-paid/classifiers/groundedness/compare_heads.py.
                    Capability.GROUNDEDNESS,
                    // Per-call: the pair head scores one output against ITS OWN input, so an inner
                    // retrieval-answer call is exactly as checkable as the outermost one.
                    Grain.OBSERVATION,
                    // Unsupportedness (1 - support) bands — see GroundednessDetector's threshold_high/
                    // threshold_low commentary. Wide separation observed in spot checks (supported
                    // ~0.95+, unsupported/contradicted ~0.01-0.10); revisit once the eval harness has
                    // measured this head's actual recall@fixed-fp.
                    "{\"threshold_high\":0.9,\"threshold_low\":0.6}",
                    // null, not a factory lambda — the ONE detectorFactory here that is null for a
                    // reason other than "not observation/turn grain" (see ClassifierModelModule's
                    // DetectorFactory javadoc). Groundedness IS observation-grain and used to close
                    // over `new GroundednessDetector(...)` here directly; that compile-time reference
                    // is exactly what made the detector class impossible to move to tessary-paid
                    // (#887/#888) without either keeping it in the open tree or breaking this file's
                    // compilation the moment its package changed. The detector is now supplied
                    // externally, by tessary-paid/groundedness's GroundednessAutoConfiguration,
                    // through the DetectorSupplier seam folded into this class's constructor below.
                    // This manifest entry still owns every OTHER fact about the classifier —
                    // catalog metadata, the operating point above, the capability and grain — because
                    // check-classifier-quality-doc.sh greps this config literal by literal path and a
                    // module split into two files here would break that pin for no gain.
                    null),
            // `tool_error` was DELETED here, not retired. It wrote a per-observation detection, and
            // EVERY new detection enqueues a grader run (ClassifierWorker.enqueueGraderRun) — so a
            // failing tool escalated a whole call site's grader set, per failing span, for a fact
            // already sitting in `tool_call.error_type`. A deterministic count does not need a jury.
            // The tool-error rate is now an aggregate read in the `vitals` slice, which writes nothing
            // and therefore cannot escalate at all.
            //
            // Dropping the key alone would only make `retireDroppedBuiltIns` DISABLE the seeded rows
            // and keep them listable — the right ending for a classifier that was withdrawn, and the
            // wrong one for a classifier that was a mistake. Migration 0030 deletes the rows, their
            // verdicts and any queued sweeps outright, so the superset L1 filter that will eventually
            // own tool failures starts from a clean table instead of inheriting this one's history.
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
                    // No measured operating point yet — there is no portable gold set for "atypical for
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
                    // USER-FACING, and re-synced onto every seeded project by the version bump below —
                    // so it names both grains now that both are measured. "This call site got slower" and
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
                    // 2: tool_duration joins turn_duration in the measure list below, with the §6.1
                    // suppression rule that keeps one event to one finding across the two grains. Both the
                    // measure list and explained_by_fraction live in defaultConfigJson, and
                    // ClassifierService.resyncBuiltIns rewrites an ALREADY-SEEDED project's definition only
                    // when the catalog version exceeds the stored one — so without this bump the second
                    // grain reaches nothing but fresh installs. Same reason frustration has bumped six
                    // times and groundedness twice.
                    //
                    // A bump rewrites configJson wholesale, so an operator's edited thresholds are replaced
                    // by the catalog's. That costs little here while the numbers below are unmeasured — there
                    // is no tuned operating point in the field to lose yet. Once the null-case run lands one,
                    // a bump starts costing something and this note stops being true.
                    //
                    // 4: max_escalations_per_sweep LEAVES the blob again. #684 made every Layer-2
                    // escalation hand-pressed, which removed the automatic path the cap was a valve on,
                    // and MetricDriftConfig lost the component in the same change — so the key has since
                    // been read by nothing, written back by nothing, and dropped silently by the first
                    // tuning edit. A number in a project's own definition that governs no behaviour is
                    // worse than no number: it reads as a dial. The bump is what removes it from the
                    // projects already carrying it.
                    4,
                    // Seeds ENABLED, like every built-in — the catalog no longer holds an unmeasured
                    // classifier back with a lifecycle. Whether this one reaches anyone but us is
                    // `duration_drift_enabled`, and while its numbers are unvalidated (see w1_floor below) that
                    // flag is the only thing standing between the guess and a partner's Triage.
                    Capability.DURATION_DRIFT,
                    // WINDOW grain: the scored unit is a stretch of one bucket's traffic summarized as a
                    // distribution. See ClassifierModelModule.Grain#WINDOW for why that is the honest
                    // answer rather than a workaround for grainFor() returning one grain per detector.
                    Grain.WINDOW,
                    // The measures this switch governs, declared HERE rather than as separate modules.
                    // "This turn was slow" and "34 of its 38 seconds were one search_docs call" are two
                    // halves of one question and therefore ONE decision a human makes, so they are one
                    // on/off switch across two candidate grains — which a single catalog Grain cannot
                    // express. MetricDriftConfig carries the per-measure grain and MetricDriftSweep reads
                    // it from there.
                    //
                    // tool_duration ships WITH the §6.1 suppression rule and never without it, because a
                    // tool finding that does not suppress the turn it explains reports one cause twice —
                    // in a stream that has no alert budget to absorb the duplicate. explained_by_fraction
                    // is that rule's one dial: how much of a turn's added time one tool's own added time
                    // has to account for. The turn shift NOTHING explains still fires alone; that is
                    // "eleven tool calls where three used to do", which is invisible at tool grain and is
                    // the whole reason turn duration is measured.
                    //
                    // Every number below is EXPERIMENT(metric-drift-tuning) and each has its rationale on
                    // the corresponding constant in MetricDriftConfig. One of them is still not measured
                    // against traffic: w1_floor = 0.139 (≈ 1.15x) is set from a SYNTHETIC null run — see
                    // that constant for the trade either side of it — and PLAN.md §9's real null case,
                    // real traffic split in half and unmodified where every firing is by construction a
                    // false positive, is what settles it. Until that run has happened this classifier
                    // belongs to orgs whose flag we set deliberately. Every OTHER
                    // number here is a window or grid setting whose rationale is on its constant; none of
                    // them is a claim about how well this detector works, and none may become one without
                    // a run.
                    "{\"measures\":[\"turn_duration\",\"tool_duration\"],\"window_target_count\":500,"
                            + "\"window_max_hours\":24,\"min_sample\":100,\"w1_floor\":0.139,"
                            + "\"explained_by_fraction\":0.5,\"settle_seconds\":300,\"hist_bins\":320}",
                    // No detector factory, exactly as behaviour drift has none: this is a per-project
                    // fitting procedure dispatched through the ClassifierSweep registered for this kind on
                    // ClassifierWorker's Grain.WINDOW branch, not an observation-grain BuiltInDetector.
                    //
                    // callSiteFactsRead() IS DELIBERATELY EMPTY, and that is worth saying because there is
                    // no object here to say it on. #654 added that seam for detectors gated on a call_site
                    // column captured from the repository (output_schema, shape): a fact landing late
                    // rewinds the sweep cursor of exactly the signals that declare it, and
                    // ClassifierModelModuleCatalogTest fails a detector that reads such a column without
                    // declaring one. This classifier reads OBSERVATION columns and the trace spine only —
                    // the bucket key is observation.call_site_id, an ingest fact present from the very
                    // first trace, and the durations are span timestamps. Nothing outside the trace can
                    // invalidate a sweep of it, so the declared set is genuinely empty rather than
                    // overlooked. (The analogous late-arriving-fact hazard for the metric classifiers is
                    // the price book, which duration does not touch and cost_drift resolves at sweep time
                    // — PROGRAM.md §3.3.)
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
                    // 3: same max_escalations_per_sweep REMOVAL as duration drift above — the two modules'
                    // blobs are kept in step so an operator reading one is not surprised by the other.
                    3,
                    // Seeds ENABLED and is held back only by its flag, exactly as duration drift is, and for
                    // the same reason: w1_floor below is simulated and PLAN.md §9's null case has not run.
                    Capability.COST_DRIFT,
                    // WINDOW grain, dispatched through this kind's registered ClassifierSweep. The scored
                    // unit is a stretch of one call site's traffic summarized as a distribution of dollars
                    // per turn.
                    Grain.WINDOW,
                    // `cost` is the ONLY measure listed, and that is the §6.1 rule expressed where it can
                    // be enforced rather than remembered: MetricDriftConfig's registry holds specs only for
                    // measures that can open a finding, so the four token buckets cannot be turned into
                    // findings by editing this blob. They are computed every window regardless — by
                    // MetricSource, folded into the window's token sidecar — and printed inside the cost
                    // finding's evidence as the decomposition that explains it.
                    //
                    // settle_seconds is READ here and ignored by duration_drift, and that asymmetry is the
                    // point (PROGRAM.md §5). Cost SUMS over a trace's spans, so it has to wait for every
                    // span to arrive; measuring early reads as cheap, which would surface as a permanent
                    // drift toward cheaper whenever ingest lags. Duration is read off a single span whose
                    // arrival IS its completion signal, so the same horizon there would delay every
                    // finding and buy nothing. MetricDriftConfig.Measured#settles carries which is which.
                    //
                    // Same EXPERIMENT(metric-drift-tuning) numbers as duration drift, and the same
                    // admission about w1_floor = 0.139: it comes off a synthetic null run, not a real one.
                    // There is a case for a HIGHER floor on cost — spend is lumpier than latency because a
                    // single retry doubles it — but simulating a second number is not better than
                    // simulating one, and PLAN.md §9's run measures both against the same corpus.
                    "{\"measures\":[\"cost\"],\"window_target_count\":500,"
                            + "\"window_max_hours\":24,\"min_sample\":100,\"w1_floor\":0.139,"
                            + "\"explained_by_fraction\":0.5,\"settle_seconds\":300,\"hist_bins\":320}",
                    // No detector factory, and callSiteFactsRead() deliberately empty, both exactly as for
                    // duration drift above — this reads observation columns (usage, model, call_site_id)
                    // and the trace spine, and nothing captured onto `call_site` from the repository. Its
                    // late-arriving-fact hazard is the PRICE BOOK, and #654's rewind seam is the wrong tool
                    // for it: rewinding rebuilds a current window but never a pinned reference, because a
                    // sketch is a running summary rather than a replayable stream. The mitigation is to
                    // resolve rates at sweep time so the gap is only as long as the deploy (PROGRAM.md
                    // §3.3), which MetricSource does.
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
                    // 1: the rebuild. The key existed before and migration 0030 deleted it outright rather
                    // than disabling it, so no project carries a row to re-sync and this starts at one.
                    1,
                    Capability.TOOL_ERROR,
                    // WINDOW: the scored unit is a stretch of one tool's traffic, tested against that
                    // tool's own earlier stretch. It was NONE for as long as the recompute lived in
                    // ToolErrorCaseSource and ran on the case reconciler's read — the only classifier
                    // whose findings advanced when somebody happened to open Triage. Its own sweep now
                    // drives it from the worker like every other classifier, and the WINDOW branch looks
                    // this detector kind up in ClassifierSweepRegistry rather than falling into metric
                    // drift (which is what the duplicate baselines and duplicate findings in the note this
                    // replaces actually came from — there is no fallthrough arm left to fall into).
                    Grain.WINDOW,
                    // Every key here is one ToolErrorConfig parses. decision_interval is the CUSUM threshold
                    // and it is a GUESS: 6.0 buys a false alarm about every 250,000 calls under independent
                    // Bernoulli trials (classifiers/tool_error/arl.py computes it), and real tool failures
                    // are bursty in a way that arithmetic cannot price. PROGRAM.md §12's null run against a
                    // real corpus is what replaces it, and it will very likely move UP.
                    //
                    // min_effect_size is not a second threshold on the same thing — it is the guard that
                    // makes a sequential test usable at volume. A CUSUM accumulates evidence indefinitely,
                    // so on a busy tool it eventually crosses on a tenth of a percentage point, which is
                    // real and is nobody's problem (PROGRAM.md §4.4).
                    "{\"decision_interval\":6.0,\"shift_multiple\":2.0,\"shift_floor\":0.005,"
                            + "\"min_effect_size\":0.05,\"min_baseline_calls\":500,"
                            + "\"down_arm_min_rate\":0.01,\"settle_seconds\":300,\"max_patterns\":8}",
                    // No detector factory and no sweep. callSiteFactsRead() is moot for the same reason it
                    // is empty on the metric modules and then some: this reads tool_call, the observation
                    // attribute bag and the trace spine, nothing captured onto call_site from a repository —
                    // and there is no cursor for a late-arriving fact to rewind even if there were.
                    null),
            new ClassifierModelModule(
                    "sop_conformance",
                    "SOP Conformance",
                    // USER-FACING. It says both halves — per-turn conformance and windowed drift —
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
                    // 4: the USER-FACING description above gains the second gate annotation
                    // ("gate precision degraded (PACC)", frozen decision 15). No config key moved,
                    // but description re-sync rides the same version gate as the blob
                    // (ClassifierService.seedBuiltIns updates a row only when version advances), so
                    // without the bump every already-seeded project would keep describing exactly
                    // one gate annotation while its case narratives surface two.
                    //
                    // 3: shadow_mode joins the blob — the validation-ladder switch that lets a project
                    // score real traffic while surfacing nothing. Same reason as the v2 bump, and the
                    // reason this key in particular could not be shipped without one: a project seeded
                    // before it existed would read the key as absent, default to SURFACING, and the
                    // shadow switch would be a no-op that looks shipped. Bumping re-syncs the blob onto
                    // every already-seeded row.
                    //
                    // 2: the serving knobs (settle_seconds, drift_window_turns) joined the blob when
                    // conformance's own sweep started dispatching this kind.
                    4,
                    // Seeds ENABLED, like every built-in — the lifecycle axis is gone — and is held back
                    // by its flag alone, which is targeted on for NO org yet, ours included: the engine's
                    // zero-false-alarm figure was measured on one corpus with synthetic injected drift,
                    // the accepted serving plan still owes the classify-service /embed endpoint, and no
                    // per-project bundle exists until an operator deploys one (ConformanceArtifactStore).
                    // Behind the flag, an enabled-but-bundle-less row's sweep is a cursor-preserving
                    // no-op, which is the second fence.
                    Capability.SOP_CONFORMANCE,
                    // WINDOW grain: the drift test fires on a window of a rule's admitted activations,
                    // never on one turn — a per-turn verdict row is evidence, not a finding. An
                    // enabled row rides ClassifierWorker's WINDOW branch, which resolves this kind to its
                    // own registered sweep — one lookup, no ordering between classifiers to get wrong.
                    Grain.WINDOW,
                    // measures stays [] as belt-and-braces: dispatch now resolves this kind to its own
                    // sweep, but if that routing ever regresses, the empty list keeps the metric-drift fold
                    // inert (an ABSENT list falls back to the duration measures, which would open duration
                    // findings under the conformance switch). alpha /
                    // min_activations / min_effect are the engine's frozen drift knobs, parsed by
                    // conformance.ConformanceConfig: e07's measured zero-false-alarm operating point
                    // (alpha 0.01 Bonferroni across the SOP's rules, windows of >= 30 activations,
                    // 0.10 minimum effect — which doubles as the gate sentinel's effect floor, per
                    // e13). settle_seconds and drift_window_turns are SERVING knobs with no engine
                    // counterpart (the engine scores a finished file; the sweep scores a live
                    // stream): how old a turn's trace must be before it is scored, and how many
                    // stored per-turn verdicts one rule's windowed drift test reads. shadow_mode is
                    // the third, and the only one about who SEES the output rather than how it is
                    // computed: true keeps scoring and recording while withholding automatic
                    // escalation and case opening (ConformanceShadowMode). Seeded false — a project
                    // opts INTO shadow, so a detector can never be muted by an absent key.
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
        //    the exchange, WARN because a user tracker feeds metrics, not incident escalation. Nothing in
        //    the platform mints one of these any more — the deep-search promote path that did was deleted
        //    with the reranker lane — but the kind stays dispatched so rows already on disk keep scoring.
        BuiltInDetector regex = new RegexDetector(
                Kind.REGEX,
                ClassifierField.BOTH,
                Detection.Severity.WARN,
                List.of(),
                true,
                new DeterministicNlPhraseCompiler(),
                mapper);

        // toUnmodifiableMap fails loud on a duplicate detector kind (IllegalStateException) — the
        // same fail-loud invariant the pre-manifest catalog had, kept via the collector framework
        // rather than an explicit constructor throw (which SpotBugs forbids, CT_CONSTRUCTOR_THROW).
        // A module with no factory is one of the five fitting-tier classifiers (behaviour drift is
        // trace-grain through the TrajectoryDetector port; duration drift, cost drift, SOP
        // conformance and tool errors are window-grain — all five dispatched by the ClassifierSweep
        // registered for their kind) OR groundedness, whose detector is instead supplied through
        // `discovered` below.
        //
        // `discovered` is the third, GENERIC source (#887/#888): any DetectorSupplier bean on the
        // classpath is folded in for the kind it claims, with NO check against MODULES membership —
        // see DetectorSupplier's class comment for the failure mode that trades for. ObjectProvider,
        // not List<DetectorSupplier>, for the reason ClassifierSweepRegistry already documents:
        // Spring treats a required List with no candidate bean as an unsatisfied dependency, and an
        // edition (or a test) with nothing discovered must be an ordinary empty answer, not a boot
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
     * The sweep {@link Grain} a {@code signal.detector} value scores at —
     * what ONE scored unit is for that classifier. Defaults to {@link Grain#OBSERVATION}
     * for anything not declared by a module (user-authored regex/classifiers, and inert or
     * unknown kinds), which is both the historical behaviour and the right default: a classifier is
     * per-span unless it says otherwise.
     */
    public Grain grainFor(String detectorKind) {
        return grains.getOrDefault(detectorKind, Grain.OBSERVATION);
    }
}
