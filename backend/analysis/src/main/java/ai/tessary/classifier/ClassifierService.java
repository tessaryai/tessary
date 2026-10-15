// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog.BuiltIn;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricControl;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricDriftDetector;
import ai.tessary.classifier.metric.MetricSketch;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.classifier.worker.ClassifierSweep;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.classifier.worker.SweepRouting;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.GroundednessProperties;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.PlatformCreditExhausted;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llm.decisions.DecisionTarget;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.pipeline.CallSiteFact;
import ai.tessary.plan.CapabilityService;
import ai.tessary.plan.EncoderAvailability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The definition side of classifiers: catalog seeding, enable/disable, and listing definitions with their detections.
 * Evaluation lives in {@link ClassifierWorker}.
 */
@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);

    private final MetricBaselineRepository baselines;

    private final ClassifierRepository signals;
    private final ClassifierDetectionRepository detections;
    private final ClassifierJobRepository jobs;
    private final BuiltInClassifierCatalog catalog;
    private final SweepRouting routing;
    private final SubstrateReadRepository substrate;
    private final ObjectMapper mapper;
    private final ClassifierProperties props;
    private final CapabilityService capabilities;
    private final ProjectRepository projects;
    private final DecisionProviderResolver decisionProviders;
    private final EncoderAvailability encoder;
    private final GroundednessProperties groundedness;

    /** So the DEBUG line is written when the skip reason changes, not once a minute while the model is down. */
    private final Map<String, String> lastEncoderSkip = new ConcurrentHashMap<>();

    public ClassifierService(
            ClassifierRepository signals,
            ClassifierDetectionRepository detections,
            ClassifierJobRepository jobs,
            BuiltInClassifierCatalog catalog,
            SweepRouting routing,
            SubstrateReadRepository substrate,
            ObjectMapper mapper,
            ClassifierProperties props,
            CapabilityService capabilities,
            ProjectRepository projects,
            MetricBaselineRepository baselines,
            DecisionProviderResolver decisionProviders,
            EncoderAvailability encoder,
            GroundednessProperties groundedness) {
        this.encoder = encoder;
        this.groundedness = groundedness;
        this.baselines = baselines;
        this.decisionProviders = decisionProviders;
        this.signals = signals;
        this.detections = detections;
        this.jobs = jobs;
        this.catalog = catalog;
        this.routing = routing;
        this.substrate = substrate;
        this.mapper = mapper;
        this.props = props;
        this.capabilities = capabilities;
        this.projects = projects;
    }

    /**
     * Seed the built-in catalog idempotently: insert missing built-ins, re-sync ones whose catalog version advanced
     * (enabled state preserved). Called on project creation and by the periodic reconcile. A project whose org cannot
     * be resolved seeds nothing rather than everything.
     */
    public int seedBuiltIns(String projectId) {
        return seedBuiltIns(projectId, withheldBuiltInKeys(projectId), signals.listByProject(projectId));
    }

    /**
     * Reads the row set once, so a correct project costs one {@code classifier} read and one {@code org_plan} read and
     * writes nothing.
     */
    private int seedBuiltIns(String projectId, Set<String> withheld, List<ClassifierRow> existingRows) {
        Map<String, ClassifierRow> byKey = new HashMap<>();
        for (ClassifierRow row : existingRows) {
            byKey.put(row.classifierKey(), row);
        }
        int inserted = 0;
        String now = Instant.now().toString();
        for (BuiltIn b : availableBuiltIns(withheld)) {
            ClassifierRow existing = byKey.get(b.classifierKey());
            if (existing == null) {
                signals.insert(new ClassifierRow(
                        Ids.ulid(),
                        projectId,
                        b.classifierKey(),
                        b.name(),
                        b.description(),
                        b.detector(),
                        b.defaultConfigJson(),
                        true,
                        b.version(),
                        // Every built-in seeds ON except one that spends the org's own provider credit.
                        b.defaultEnabled(),
                        b.defaultMode(),
                        now,
                        now,
                        null));
                inserted++;
            } else if (existing.builtIn() && existing.version() < b.version()) {
                signals.updateDefinition(new ClassifierRow(
                        existing.id(),
                        projectId,
                        b.classifierKey(),
                        b.name(),
                        b.description(),
                        b.detector(),
                        b.defaultConfigJson(),
                        true,
                        b.version(),
                        existing.enabled(),
                        existing.mode(),
                        existing.createdAt(),
                        now,
                        existing.callSiteIds()));
            }
        }
        if (inserted > 0) {
            log.info(Markers.OPS, "signal catalog seeded project={} inserted={}", projectId, inserted);
        }
        return inserted;
    }

    /**
     * Re-sync a project against the current catalog, heartbeat-safe: insert unseen built-ins, re-sync advanced
     * versions, and disable (never delete) rows whose key left the catalog. Trace traffic must not trigger this: which
     * classifiers an org has is a licensing question only the flag layer answers.
     */
    public int resyncBuiltIns(Project project) {
        return resync(project.id(), withheldForOrg(project.orgId()));
    }

    /**
     * Seeding only inserts catalog keys and retirement only touches non-catalog keys, so one pre-seed snapshot is exact
     * for both.
     */
    private int resync(String projectId, Set<String> withheld) {
        List<ClassifierRow> rows = signals.listByProject(projectId);
        int inserted = seedBuiltIns(projectId, withheld, rows);
        retireDroppedBuiltIns(projectId, rows);
        return inserted;
    }

    /** Empty when the project has no resolvable org: a data fault, not a reason to hand out every classifier. */
    private List<BuiltIn> availableBuiltIns(Set<String> withheld) {
        return catalog.builtIns().stream()
                .filter(b -> !withheld.contains(b.classifierKey()))
                .toList();
    }

    /**
     * The catalog keys this org does NOT have. Withholding never writes: the row keeps its {@code enabled} column and
     * is suppressed at every read and dispatch, so flipping the flag back restores it exactly. Fail-closed when the
     * project has no org.
     */
    private Set<String> withheldBuiltInKeys(String projectId) {
        return projects.findById(projectId)
                .map(project -> withheldForOrg(project.orgId()))
                .orElseGet(() -> {
                    log.warn(Markers.OPS, "signal catalog withheld in full: project has no org project={}", projectId);
                    return catalog.builtIns().stream()
                            .map(BuiltIn::classifierKey)
                            .collect(Collectors.toUnmodifiableSet());
                });
    }

    /**
     * The part that costs anything: {@code CapabilityService#resolve} reads the org's overrides, cached ten seconds per
     * org.
     */
    private Set<String> withheldForOrg(String orgId) {
        CapabilityService.CapabilitySet enabled = capabilities.resolve(orgId);
        return catalog.builtIns().stream()
                .filter(b -> !enabled.isEnabled(b.capability()))
                .map(BuiltIn::classifierKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * User-authored rows and built-ins retired from the catalog are never withheld, so retired history stays readable.
     */
    private static boolean reaches(ClassifierRow row, Set<String> withheldBuiltInKeys) {
        return !row.builtIn() || !withheldBuiltInKeys.contains(row.classifierKey());
    }

    /**
     * {@link #withheldBuiltInKeys} keyed by detector, for the shared {@code finding} table, which carries no classifier
     * column.
     */
    public Set<String> unavailableDetectorKinds(String projectId) {
        Set<String> withheldKeys = withheldBuiltInKeys(projectId);
        return catalog.builtIns().stream()
                .filter(b -> withheldKeys.contains(b.classifierKey()))
                .map(BuiltIn::detector)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Disable (never delete) enabled built-in rows whose key left the catalog. Keys on catalog membership, NOT
     * {@link #availableBuiltIns}: a flagged-off row must survive so re-enabling the flag restores it.
     */
    private void retireDroppedBuiltIns(String projectId, List<ClassifierRow> rows) {
        Set<String> catalogKeys =
                catalog.builtIns().stream().map(BuiltIn::classifierKey).collect(Collectors.toUnmodifiableSet());
        for (ClassifierRow row : rows) {
            if (row.builtIn() && row.enabled() && !catalogKeys.contains(row.classifierKey())) {
                signals.setEnabled(projectId, row.id(), false);
                log.info(
                        Markers.OPS,
                        "signal built-in retired (disabled, history kept) project={} signal={}",
                        projectId,
                        row.classifierKey());
            }
        }
    }

    /**
     * A call-site fact a detector gates on has landed or changed: rewind every signal that declares it, so history
     * scored {@code none()} only for lack of the fact is re-read rather than reading as "nothing malformed".
     * Whole-signal because the cursor is; re-scoring is idempotent. Disabled signals are rewound too, since re-enabling
     * does not reset a cursor.
     */
    public void rewindForCallSiteFact(String projectId, CallSiteFact fact, Set<String> callSiteIds) {
        for (ClassifierRow row : signals.listByProject(projectId)) {
            BuiltInDetector detector = catalog.detectorFor(row.detector());
            if (detector == null || !detector.callSiteFactsRead().contains(fact)) continue;
            if (jobs.rewindCursor(projectId, row.id()) > 0) {
                log.info(
                        Markers.OPS,
                        "signal sweep rewound for call-site fact project={} signal={} fact={} callSites={}",
                        projectId,
                        row.classifierKey(),
                        fact,
                        callSiteIds);
            } else {
                // Silence here would make an unrewound cursor indistinguishable from a successful rewind.
                log.warn(
                        Markers.OPS,
                        "signal sweep NOT rewound (job in flight, dead-lettered, or absent) "
                                + "project={} signal={} fact={} callSites={}",
                        projectId,
                        row.classifierKey(),
                        fact,
                        callSiteIds);
            }
        }
    }

    public List<ClassifierRow> list(String projectId) {
        Set<String> withheld = withheldBuiltInKeys(projectId);
        return signals.listByProject(projectId).stream()
                .filter(row -> reaches(row, withheld))
                .toList();
    }

    /**
     * The guard every per-classifier endpoint funnels through. A withheld built-in is a 404, not a 403: a 403 would
     * itself reveal a capability the org does not have.
     */
    public ClassifierRow get(String projectId, String id) {
        ClassifierRow row =
                signals.findById(projectId, id).orElseThrow(() -> new TessaryException(ClassifierError.NOT_FOUND, id));
        if (!reaches(row, withheldBuiltInKeys(projectId))) {
            throw new TessaryException(ClassifierError.NOT_FOUND, id);
        }
        return row;
    }

    /** A state, not a switch: disabling would be somebody's decision, and re-enabling does not rewind the sweep. */
    public @Nullable String readiness(String projectId, ClassifierRow row) {
        if (BuiltInDetector.Kind.FRUSTRATION.equals(row.detector())) {
            if (!row.enabled()) return null;
            return signals.findPause(projectId, row.id())
                    .map(ClassifierPause::reason)
                    .orElse(null);
        }
        if (!BuiltInDetector.Kind.MALFORMED_OUTPUT.equals(row.detector())) return null;
        return substrate.anyOutputSchema(projectId) ? null : ClassifierDtos.ClassifierView.WAITING_ON_SCHEMAS;
    }

    /**
     * One row per signal even if no job was ever enqueued, so a new or disabled signal reads healthy rather than
     * absent.
     */
    public List<ClassifierDtos.ClassifierHealthView> health(String projectId) {
        Map<String, ClassifierJobRow> byId = jobs.listByProject(projectId).stream()
                .collect(Collectors.toMap(ClassifierJobRow::classifierId, r -> r));
        return list(projectId).stream()
                .map(s -> ClassifierDtos.ClassifierHealthView.of(s.id(), byId.get(s.id()), props.maxAttempts()))
                .toList();
    }

    /**
     * Guarded by {@link #get} first, so a withheld built-in 404s before any write. Enabling a provider-calling
     * classifier with no runnable provider is refused with {@link ClassifierError#PROVIDER_REQUIRED}; any enable clears
     * a pause.
     */
    public ClassifierRow setEnabled(String projectId, String id, boolean enabled) {
        ClassifierRow row = get(projectId, id);
        if (enabled) routing.sweepFor(row.detector()).ifPresent(sweep -> checkEnable(sweep, projectId, row));
        if (enabled
                && BuiltInDetector.Kind.FRUSTRATION.equals(row.detector())
                && !decisionProviders.hasProvider(projectId, ModelLane.FRUSTRATION)) {
            throw new TessaryException(ClassifierError.PROVIDER_REQUIRED, row.name());
        }
        if (signals.setEnabled(projectId, id, enabled) == 0) {
            throw new TessaryException(ClassifierError.NOT_FOUND, id);
        }
        if (enabled) signals.unpause(projectId, id);
        return get(projectId, id);
    }

    private static void checkEnable(ClassifierSweep sweep, String projectId, ClassifierRow row) {
        try {
            sweep.checkEnable(row);
        } catch (TessaryException e) {
            StructuredLog.info(log, Markers.OPS, "classifier.enable.refused")
                    .field("project", projectId)
                    .field("classifier", row.id())
                    .field("detector", row.detector())
                    .field("error_code", e.error().code())
                    .log();
            throw e;
        }
    }

    /**
     * The org just saved {@code provider}'s key, so lift the pause on classifiers whose lane now runs on it rather than
     * waiting out the credential retry.
     */
    public int unpauseForProvider(String orgId, ModelProvider provider) {
        int lifted = 0;
        for (Project project : projects.findByOrg(orgId)) {
            for (ClassifierRow row : signals.listByProject(project.id())) {
                if (!BuiltInDetector.Kind.FRUSTRATION.equals(row.detector())) continue;
                if (signals.findPause(project.id(), row.id()).isEmpty()) continue;
                Optional<DecisionTarget> target;
                try {
                    target = decisionProviders.resolve(project.id(), ModelLane.FRUSTRATION);
                } catch (TessaryException e) {
                    if (!(e instanceof PlatformCreditExhausted)) throw e;
                    continue;
                }
                if (target.isEmpty() || target.get().provider() != provider) continue;
                lifted += signals.unpause(project.id(), row.id());
            }
        }
        return lifted;
    }

    /**
     * Adding a call site rewinds a classifier that scores spans with no model, so the new call site's history is
     * checked rather than read as clean. Model-calling classifiers are not rewound (it would resend the whole history),
     * nor window classifiers, whose replay would double-count fitted state.
     */
    public ClassifierRow setCallSiteIds(String projectId, String id, @Nullable List<String> callSiteIds) {
        ClassifierRow before = get(projectId, id);
        if (BuiltInDetector.Kind.TOOL_ERROR.equals(before.detector())) {
            throw new TessaryException(ClassifierError.CALL_SITE_SCOPE_UNSUPPORTED, before.classifierKey());
        }
        List<String> scope = callSiteIds == null
                ? null
                : callSiteIds.stream().distinct().sorted().toList();
        if (scope != null) {
            Set<String> known = substrate.knownCallSiteIds(projectId);
            List<String> unknown =
                    scope.stream().filter(c -> !known.contains(c)).toList();
            if (!unknown.isEmpty()) {
                throw new TessaryException(ClassifierError.UNKNOWN_CALL_SITE, String.join("', '", unknown));
            }
        }
        if (signals.setCallSiteIds(projectId, id, scope) == 0) {
            throw new TessaryException(ClassifierError.NOT_FOUND, id);
        }
        List<String> was = before.callSiteIds();
        boolean widened = was != null && (scope == null || !was.containsAll(scope));
        boolean rewound = widened && rewindsOnWiden(before.detector()) && jobs.rewindCursor(projectId, id) > 0;
        StructuredLog.info(log, Markers.OPS, "classifier.call-sites.set")
                .message(
                        "%s now runs on %s%s",
                        before.classifierKey(),
                        scope == null ? "every call site" : scope.size() + " call site(s)",
                        rewound ? ", cursor rewound" : "")
                .field("project", projectId)
                .field("signal", before.classifierKey())
                .field("callSites", scope == null ? -1 : scope.size())
                .field("widened", widened)
                .field("rewound", rewound)
                .log();
        return get(projectId, id);
    }

    public List<String> knownCallSiteIds(String projectId) {
        return List.copyOf(substrate.knownCallSiteIds(projectId));
    }

    private boolean rewindsOnWiden(String detector) {
        return routing.grainFor(detector) == ClassifierModelModule.Grain.OBSERVATION
                && !BuiltInDetector.Kind.ENCODER_BACKED.contains(detector);
    }

    /**
     * Exactly what {@link MetricDriftSweep} reads next pass. Throws {@link ClassifierError#NOT_METRIC_DRIFT} for any
     * other detector.
     */
    public ClassifierDtos.TuningView getTuning(String projectId, String id) {
        ClassifierRow signal = requireMetricDrift(projectId, id);
        MetricDriftConfig config = MetricDriftConfig.of(mapper, signal.configJson());
        return ClassifierDtos.TuningView.of(config, impliedFalseAlarmRate(projectId, signal, config));
    }

    /**
     * Merged into the existing parsed config so untouched fields survive. {@link MetricDriftConfig} clamps every field,
     * so the value read back is the one in effect.
     */
    public ClassifierDtos.TuningView setTuning(
            String projectId, String id, int windowTargetCount, int windowMaxHours, int minSample, double w1Floor) {
        ClassifierRow signal = requireMetricDrift(projectId, id);
        MetricDriftConfig current = MetricDriftConfig.of(mapper, signal.configJson());
        MetricDriftConfig tuned = new MetricDriftConfig(
                current.measures(),
                windowTargetCount,
                windowMaxHours,
                minSample,
                w1Floor,
                current.explainedByFraction(),
                current.settleSeconds(),
                current.histBins());
        signals.updateConfig(projectId, id, tuned.toJson(mapper));
        log.info(Markers.OPS, "signal tuning updated project={} signal={}", projectId, signal.classifierKey());
        return ClassifierDtos.TuningView.of(tuned, impliedFalseAlarmRate(projectId, signal, tuned));
    }

    /**
     * What the configured move costs in false alarms on this project's traffic, or null without enough traffic. Uses
     * the median bucket's spread, since one pathological bucket would dominate a mean. Stated at two full windows;
     * thinner windows run quieter.
     */
    private @Nullable Double impliedFalseAlarmRate(String projectId, ClassifierRow signal, MetricDriftConfig config) {
        List<Double> spreads = new ArrayList<>();
        for (MetricBaselineRow row : baselines.listByClassifier(projectId, signal.id())) {
            MetricControl.Day recent = MetricControl.fromJson(row.controlJson()).newest();
            String json = row.pinnedSketchJson() != null
                    ? row.pinnedSketchJson()
                    : (recent == null ? null : recent.sketchJson());
            if (json == null) continue;
            try {
                double sigma = MetricSketch.fromJson(json).stdDevLog();
                if (sigma > 0) spreads.add(sigma);
            } catch (RuntimeException e) {
                // One unreadable baseline must not cost the whole answer.
                log.debug("unreadable baseline sketch on {} while estimating spread", row.id(), e);
            }
        }
        if (spreads.isEmpty()) return null;
        Collections.sort(spreads);
        double median = spreads.get(spreads.size() / 2);
        return MetricDriftDetector.impliedFalseAlarmRate(config.w1Floor(), median, config.windowTargetCount()).stream()
                .boxed()
                .findFirst()
                .orElse(null);
    }

    private ClassifierRow requireMetricDrift(String projectId, String id) {
        ClassifierRow signal = get(projectId, id);
        if (!BuiltInDetector.Kind.COST_DRIFT.equals(signal.detector())
                && !BuiltInDetector.Kind.DURATION_DRIFT.equals(signal.detector())) {
            throw new TessaryException(ClassifierError.NOT_METRIC_DRIFT, id);
        }
        return signal;
    }

    public List<ClassifierDtos.ClassifierEventView> eventsForClassifier(
            String projectId, String classifierId, @Nullable String mode, int limit) {
        ClassifierRow signal = get(projectId, classifierId);
        // Unbanded NULL confidence reads as high.
        boolean trackingOnly = ClassifierRow.Mode.TRACKING.equals(mode);
        return detections.listByClassifierKey(projectId, signal.classifierKey(), trackingOnly, limit);
    }

    /**
     * Trailing UTC days, oldest first, zero-filled and aligned with {@code days()}, with an entry for every classifier
     * so a silent one shows a flat strip. Bucketed by {@code subject_started_at}, the clock the project totals use, so
     * backfilled bars land on the right days.
     */
    public DailyVolume dailyVolume(String projectId, int days) {
        int d = Math.clamp(days, 1, 30);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today.minusDays(d - 1L);
        Instant from = first.atStartOfDay(ZoneOffset.UTC).toInstant();

        List<String> dayLabels = new ArrayList<>(d);
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            dayLabels.add(day.toString());
        }

        long[] traceTotals = new long[d];
        for (SubstrateReadRepository.DailyTraceCount t : substrate.dailyTraceCounts(projectId, from)) {
            int i = (int) ChronoUnit.DAYS.between(first, t.day());
            if (i >= 0 && i < d) traceTotals[i] = t.total();
        }

        Map<String, long[]> countsBySignal = new HashMap<>();
        for (ClassifierDetectionRepository.DailyClassifierCount c : detections.dailyDetectionCounts(projectId, from)) {
            int i = (int) ChronoUnit.DAYS.between(first, c.day());
            if (i >= 0 && i < d) {
                countsBySignal.computeIfAbsent(c.classifierId(), k -> new long[d])[i] = c.traces();
            }
        }

        // list(), not listByProject(): a withheld built-in's series would have no row to name it.
        List<ClassifierDailyCounts> perClassifier = list(projectId).stream()
                .map(s -> new ClassifierDailyCounts(s.id(), countsBySignal.getOrDefault(s.id(), new long[d])))
                .toList();
        return new DailyVolume(dayLabels, traceTotals, perClassifier);
    }

    public record DailyVolume(List<String> days, long[] traceTotals, List<ClassifierDailyCounts> classifiers) {}

    public record ClassifierDailyCounts(String classifierId, long[] counts) {}

    /**
     * Ensure a pending sweep job for every enabled signal. A withheld built-in is not enqueued, so it stops costing
     * within a heartbeat; its {@code enabled} column is untouched. An encoder-backed classifier is skipped while
     * {@link #encoderSkipReason} gives a reason; a skip touches nothing.
     */
    public void enqueueEnabled(String projectId) {
        Set<String> withheld = withheldBuiltInKeys(projectId);
        Instant now = Instant.now();
        for (ClassifierRow s : signals.listEnabled(projectId)) {
            if (!reaches(s, withheld)) continue;
            if (BuiltInDetector.Kind.ENCODER_BACKED.contains(s.detector())) {
                Optional<String> skip = encoderSkipReason(projectId, s.id(), now);
                if (skip.isPresent()) {
                    logEncoderSkip(projectId, s, skip.get());
                    continue;
                }
                lastEncoderSkip.remove(s.id());
            }
            jobs.enqueue(projectId, s.id(), props.deadLetterCooldownSeconds());
        }
    }

    /**
     * Empty when it should run. Otherwise the model does not answer, or in production mode its last sweep reached the
     * head less than {@code tessary.groundedness.production-sleep-minutes} ago, which lets the GPU instance idle. Dev
     * mode never sleeps.
     */
    Optional<String> encoderSkipReason(String projectId, String classifierId, Instant now) {
        if (!encoder.available())
            return Optional.of("model down: " + encoder.snapshot().reason());
        if (groundedness.mode() != GroundednessProperties.Mode.PRODUCTION) return Optional.empty();
        Optional<Instant> caughtUp = jobs.caughtUpAt(projectId, classifierId);
        if (caughtUp.isPresent()
                && now.isBefore(caughtUp.get().plus(Duration.ofMinutes(groundedness.getProductionSleepMinutes())))) {
            return Optional.of("sleeping after the sweep that caught up at " + caughtUp.get());
        }
        return Optional.empty();
    }

    /** Asked by the worker for a job that was pending before the model went away. */
    public boolean encoderDown(ClassifierRow row) {
        return BuiltInDetector.Kind.ENCODER_BACKED.contains(row.detector()) && !encoder.available();
    }

    private void logEncoderSkip(String projectId, ClassifierRow row, String reason) {
        if (reason.equals(lastEncoderSkip.put(row.id(), reason))) return;
        StructuredLog.debug(log, "groundedness.sweep.skipped")
                .message("not sweeping %s: %s", row.classifierKey(), reason)
                .field("project", projectId)
                .field("signal", row.classifierKey())
                .field("classifierId", row.id())
                .field("mode", groundedness.getClassifierMode())
                .field("reason", reason)
                .log();
    }
}
