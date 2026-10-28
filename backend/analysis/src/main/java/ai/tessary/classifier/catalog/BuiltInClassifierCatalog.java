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
 * The built-in classifiers, declared once each as {@link ClassifierModelModule} manifests from which the
 * {@link BuiltIn} list and detector dispatch are derived. No built-in makes a per-observation LLM call except
 * Frustration, which runs on the org's own key. Catalog membership is not availability: a capability flag decides
 * whether a module reaches an org, and turning one off must always be the flag, never a deletion here, which would
 * disable it everywhere for good.
 */
@Component
public class BuiltInClassifierCatalog {

    /**
     * Frustration seeds disabled because it spends the org's own provider credit, and Groundedness because it needs a
     * model server set up first.
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
             * Only for a freshly seeded row: {@code ClassifierService} preserves a tenant's stored mode across a
             * re-seed.
             */
            String defaultMode,
            boolean defaultEnabled) {}

    /** Detector factories close only over the {@link Deps} passed at build time, so this list is static. */
    static final List<ClassifierModelModule> MODULES = List.of(
            new ClassifierModelModule(
                    "frustration",
                    "Frustration",
                    // Re-synced onto every seeded project on a version bump, so a stale description lands in production
                    // rows as fact.
                    "Frustration the agent caused, judged by TypeSafe's Jev decision model on your "
                            + "OpenRouter or TypeSafe key. Off by default. Scores every call site unless you "
                            + "limit it to some. Scores a user message only when four text messages on the same call site "
                            + "precede it, and a session, one conversation on one call site, only until its "
                            + "first detection. A session counts once in each hour it has a scored message. "
                            + "Each call site learns its own normal rate of frustrated sessions by the hour "
                            + "and is watched from its first 100 session-hours with the same sequential "
                            + "test Tool Error uses, still learning its normal until it has seen 1,000; a "
                            + "case opens when the rate has risen above "
                            + "that normal. A call site that is bad from day one learns that as normal and "
                            + "is flagged only if it gets worse.",
                    Kind.FRUSTRATION,
                    // Every threshold or config change needs a version bump, or it reaches fresh installs only. A
                    // re-sync never writes classifier.call_site_ids.
                    11,
                    Capability.FRUSTRATION,
                    // TURN grain: a user message lands as several observations, so the sweep scores the first span of
                    // each call site in the turn.
                    Grain.TURN,
                    // threshold is the cutoff on P(unhappy_with_assistant), hashed into the scorer version; the rest
                    // are the rate test's dials. EXPERIMENT(frustration-tuning): threshold, arl_target and
                    // min_decision_interval are starting values until a null replay on real traffic settles them.
                    "{\"threshold\":0.40,\"arl_target\":10000,\"min_decision_interval\":4,"
                            + "\"shift_multiple\":2.0,\"shift_floor\":0.02,"
                            + "\"min_baseline_conversations\":100,\"freeze_baseline_conversations\":1000}",
                    // null: JevFrustrationDetector is a Spring bean supplied through DetectorSupplier, since it needs
                    // dependencies that are not Deps.
                    null,
                    // TRACKING: Jev writes only the HIGH band, so both modes read the same rows.
                    ClassifierRow.Mode.TRACKING,
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
                    // Any span can leak a credential, inner calls included.
                    Grain.OBSERVATION,
                    // Armed at one: a single leaked credential is the whole incident. Only the HIGH band counts toward
                    // the bar.
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
                    Grain.OBSERVATION,
                    null,
                    d -> new MalformedOutputDetector(d.substrate(), d.mapper())),
            new ClassifierModelModule(
                    "groundedness",
                    "Groundedness",
                    "Answers that state things the retrieved documents don't support.",
                    Kind.GROUNDEDNESS,
                    // A version bump rewrites configJson wholesale, which is what dropped the arming block and second
                    // band from projects seeded before v7. Evidence reaches the head as a LIST of documents, the layout
                    // the checkpoint was trained on.
                    7,
                    Capability.GROUNDEDNESS,
                    // Per-call: the head scores one output against its own input, so inner calls are as checkable as
                    // the outermost.
                    Grain.OBSERVATION,
                    // threshold is the cutoff on P(unsupported), the 2% false-alarm point on RAGTruth test, hashed into
                    // the scorer version; the rest are the rate test's dials, in traces.
                    "{\"threshold\":0.975,\"arl_target\":50000,\"min_decision_interval\":4,"
                            + "\"shift_multiple\":2.0,\"shift_floor\":0.02,"
                            + "\"min_baseline_traces\":100,\"freeze_baseline_traces\":1000}",
                    // null: the detector writes its own groundedness_assessment rows, so it arrives through
                    // DetectorSupplier with its repository.
                    null,
                    // TRACKING: the detector writes only the HIGH band.
                    ClassifierRow.Mode.TRACKING,
                    false),
            new ClassifierModelModule(
                    "duration_drift",
                    "Duration Drift",
                    // Re-synced by the version bump, so it names both grains.
                    "This call site's turns — and the individual tool calls inside them — are taking "
                            + "measurably longer, or shorter, than they recently did. Compares each "
                            + "population's own recent distribution against its own earlier one, so nobody "
                            + "has to know what \"slow\" means for a call site they have never read: the "
                            + "bar is that population's own past. A turn that is slow because one tool is "
                            + "slow reports once, naming the tool. Labels no individual trace, because a "
                            + "forty-second research run and a two-second lookup are both routinely "
                            + "correct.",
                    Kind.DURATION_DRIFT,
                    // A version bump replaces an operator's edited thresholds on every seeded project. Drop a key with
                    // no reader in MetricDriftConfig in the same bump that orphans it.
                    4,
                    // While its numbers are unvalidated (see w1_floor) the `duration_drift_enabled` flag is all that
                    // keeps this out of a partner's Triage.
                    Capability.DURATION_DRIFT,
                    Grain.WINDOW,
                    // Both measures sit behind one switch, since "this turn was slow" and "a tool inside it was slow"
                    // are one question at two grains. tool_duration must ship with the suppression rule, or one cause
                    // is reported twice. w1_floor (0.139, about 1.15x) comes from a synthetic null run; a real
                    // null-case run settles it.
                    "{\"measures\":[\"turn_duration\",\"tool_duration\"],\"window_target_count\":500,"
                            + "\"window_max_hours\":24,\"min_sample\":100,\"w1_floor\":0.139,"
                            + "\"explained_by_fraction\":0.5,\"settle_seconds\":300,\"hist_bins\":320}",
                    // No detector factory: dispatched through the ClassifierSweep registered for this kind.
                    // callSiteFactsRead() is empty because it reads only observation columns and the trace spine,
                    // present from the first trace.
                    null),
            new ClassifierModelModule(
                    "cost_drift",
                    "Cost Drift",
                    // Names the decomposition, which is what separates this from a spend chart.
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
                    3,
                    // Held back only by its flag, like duration drift: w1_floor is simulated.
                    Capability.COST_DRIFT,
                    Grain.WINDOW,
                    // `cost` is the only measure because MetricDriftConfig registers specs only for measures that may
                    // open a finding; the token buckets are evidence. settle_seconds only caps how long a page waits
                    // for a root span that never lands.
                    "{\"measures\":[\"cost\"],\"window_target_count\":500,"
                            + "\"window_max_hours\":24,\"min_sample\":100,\"w1_floor\":0.139,"
                            + "\"explained_by_fraction\":0.5,\"settle_seconds\":300,\"hist_bins\":320}",
                    // callSiteFactsRead() is empty as for duration drift. Its late-fact hazard is the price book, which
                    // the rewind seam cannot fix because it never rebuilds a pinned reference.
                    null),
            new ClassifierModelModule(
                    "tool_error",
                    "Tool Errors",
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
                    // WINDOW grain, looked up in ClassifierSweepRegistry rather than falling into metric drift.
                    Grain.WINDOW,
                    // decision_interval 6.0 is a guess (one false alarm per ~250,000 independent calls); real failures
                    // are bursty, and a null run on a real corpus will likely move it up.
                    "{\"decision_interval\":6.0,\"shift_multiple\":2.0,\"shift_floor\":0.005,"
                            + "\"min_effect_size\":0.05,\"min_baseline_calls\":500,"
                            + "\"down_arm_min_rate\":0.01,\"settle_seconds\":300,\"max_patterns\":8}",
                    // callSiteFactsRead() is moot: this reads nothing captured onto call_site from a repository.
                    null));

    private final List<BuiltIn> builtIns;
    private final Map<String, BuiltInDetector> detectors;
    private final Map<String, Grain> grains;

    public BuiltInClassifierCatalog(
            ObjectMapper mapper,
            SubstrateReadRepository substrate,
            EncoderScorer encoderScorer,
            ObjectProvider<DetectorSupplier> discovered) {
        Deps deps = new Deps(mapper, encoderScorer, substrate);

        this.builtIns = MODULES.stream().map(ClassifierModelModule::toBuiltIn).toList();

        // Sweep grain per detector kind, also derived from the manifests. Only kinds declared by a
        // module appear here; the dispatch-only detector below (user regex) scores
        // one span at a time and falls through to the OBSERVATION default in grainFor.
        this.grains = MODULES.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ClassifierModelModule::detectorKind, ClassifierModelModule::grain));

        // RegexDetector is dispatch-only, not a catalog built-in: nothing creates regex classifiers any more, but rows
        // already on disk keep scoring.
        BuiltInDetector regex = new RegexDetector(
                Kind.REGEX,
                ClassifierField.BOTH,
                Detection.Severity.WARN,
                true,
                new DeterministicNlPhraseCompiler(),
                mapper);

        // toUnmodifiableMap fails loud on a duplicate kind without a constructor throw, which SpotBugs forbids
        // (CT_CONSTRUCTOR_THROW). ObjectProvider rather than List, because a required List with no bean fails boot.
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

    @Nullable
    public BuiltInDetector detectorFor(String detectorKind) {
        return detectors.get(detectorKind);
    }

    /** A kind with a detector and no module, such as {@code regex}, is not declared. */
    public boolean declares(String detectorKind) {
        return grains.containsKey(detectorKind);
    }

    /**
     * OBSERVATION unless a module declares otherwise; {@code SweepRouting} answers window grain for an undeclared kind
     * a {@code ClassifierSweep} claims.
     */
    public Grain grainFor(String detectorKind) {
        return grains.getOrDefault(detectorKind, Grain.OBSERVATION);
    }
}
