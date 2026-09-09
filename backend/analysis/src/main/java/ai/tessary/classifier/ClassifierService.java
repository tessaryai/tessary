// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog.BuiltIn;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierSeedListener;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricControl;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricDriftDetector;
import ai.tessary.classifier.metric.MetricSketch;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.pipeline.CallSiteFact;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Façade for the signal feature: per-project seeding of the built-in catalog, the
 * enable/disable lifecycle, and listing definitions + their detections. The async evaluation itself
 * lives in {@link ClassifierWorker}; this service owns the definition side.
 */
@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);

    /** Read-only here, and only to answer what a chosen move costs in false alarms. */
    private final MetricBaselineRepository baselines;

    private final ClassifierRepository signals;
    private final ClassifierDetectionRepository detections;
    private final ClassifierJobRepository jobs;
    private final BuiltInClassifierCatalog catalog;
    private final SubstrateReadRepository substrate;
    private final ObjectMapper mapper;
    private final ClassifierProperties props;
    private final CapabilityService capabilities;
    private final ProjectRepository projects;

    public ClassifierService(
            ClassifierRepository signals,
            ClassifierDetectionRepository detections,
            ClassifierJobRepository jobs,
            BuiltInClassifierCatalog catalog,
            SubstrateReadRepository substrate,
            ObjectMapper mapper,
            ClassifierProperties props,
            CapabilityService capabilities,
            ProjectRepository projects,
            MetricBaselineRepository baselines) {
        this.baselines = baselines;
        this.signals = signals;
        this.detections = detections;
        this.jobs = jobs;
        this.catalog = catalog;
        this.substrate = substrate;
        this.mapper = mapper;
        this.props = props;
        this.capabilities = capabilities;
        this.projects = projects;
    }

    /**
     * Seed the built-in catalog into a project, idempotently: a missing built-in is inserted
     * enabled; an existing one whose catalog version advanced has its definition re-synced
     * (enable/disable state preserved). Returns the number newly inserted.
     *
     * <p>Two triggers: {@link ClassifierSeedListener} on project creation, and {@link
     * #resyncBuiltIns} from {@code ClassifierCatalogWorker} for every active project, which
     * self-heals projects created before the listener existed. The worker's first sweep starts
     * from a null cursor, so pre-existing trace history is classified immediately. Never called
     * from a read path.
     *
     * <p>Which built-ins are candidates at all is the flag layer's decision ({@link
     * #availableBuiltIns}): a classifier whose capability is off for this project's org is not
     * seeded. A project whose org cannot be resolved seeds nothing rather than seeding
     * everything; the reconcile retries, and an org-less project is a bug, not a licence.
     */
    public int seedBuiltIns(String projectId) {
        return seedBuiltIns(projectId, withheldBuiltInKeys(projectId), signals.listByProject(projectId));
    }

    /**
     * {@link #seedBuiltIns} over an already-resolved flag answer and an already-read row set: the
     * form the periodic reconcile calls, so one project costs a bounded number of queries rather
     * than one per catalog module.
     *
     * <p>The row set is read once and indexed by key here, replacing a {@code findByKey} per
     * built-in. The steady-state cost of a project whose catalog is already correct is one
     * {@code classifier} read and one {@code org_plan} read, and it writes nothing.
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
                        // Every built-in seeds ON. A classifier whose numbers we do not trust is
                        // held back by its capability flag, not by a second switch in the
                        // catalog; see BuiltIn.
                        true,
                        // The operating point the catalog declares for this built-in: DISCOVERY
                        // (high recall) is right for a classifier nobody has characterised yet;
                        // frustration declares TRACKING once it has been. See
                        // BuiltInClassifierCatalog for the measurement.
                        b.defaultMode(),
                        now,
                        now));
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
                        existing.mode(), // preserve the tenant's operating point across a re-seed
                        existing.createdAt(),
                        now));
            }
        }
        if (inserted > 0) {
            log.info(Markers.OPS, "signal catalog seeded project={} inserted={}", projectId, inserted);
        }
        return inserted;
    }

    /**
     * Re-sync a project against the current catalog, heartbeat-safe: a built-in whose catalog
     * version advanced has its definition updated (state preserved), and any catalog built-in
     * the project has never seen is inserted, including every built-in for a project that has
     * never been seeded. The caller ({@code ClassifierCatalogWorker}) iterates every active
     * project, so this also self-heals projects created before {@link ClassifierSeedListener}.
     *
     * <p>Trace traffic is not the trigger and must not become it: which classifiers an org has
     * is a licensing question the flag layer alone answers, and must not wait on a project's
     * first span landing.
     *
     * <p>Also the catalog's retirement path: a seeded {@code built_in=true} row whose
     * {@code classifier_key} is no longer in the catalog is disabled, never deleted, so its
     * detection history stays listable.
     *
     * @return how many built-ins this pass newly inserted (0 in the steady state)
     */
    public int resyncBuiltIns(String projectId) {
        return resync(projectId, withheldBuiltInKeys(projectId));
    }

    /**
     * {@link #resyncBuiltIns} for a caller that already holds the {@link Project} row: the periodic
     * reconcile, which got it from {@code findActive()} and would otherwise re-read it per project purely
     * to recover the {@code org_id} the capability lookup needs.
     */
    public int resyncBuiltIns(Project project) {
        return resync(project.id(), withheldForOrg(project.orgId()));
    }

    /**
     * Seed-then-retire over one read of the project's rows. Seeding only inserts keys that are
     * in the catalog and retirement only touches keys that are not, so a row inserted a line
     * earlier can never be a retirement candidate and the pre-seed snapshot is exact for both.
     */
    private int resync(String projectId, Set<String> withheld) {
        List<ClassifierRow> rows = signals.listByProject(projectId);
        // Insert-if-missing + version re-sync are exactly seedBuiltIns' semantics, so this is one
        // call, unconditional: a never-seeded project gets the full catalog too.
        int inserted = seedBuiltIns(projectId, withheld, rows);
        retireDroppedBuiltIns(projectId, rows);
        return inserted;
    }

    /**
     * The built-ins an org can have: catalog membership narrowed by the flag layer's {@link
     * #withheldBuiltInKeys} answer. Empty when the project has no resolvable org, which is a data fault,
     * not a reason to hand out every classifier.
     */
    private List<BuiltIn> availableBuiltIns(Set<String> withheld) {
        return catalog.builtIns().stream()
                .filter(b -> !withheld.contains(b.classifierKey()))
                .toList();
    }

    /**
     * The catalog keys this project's org does <b>not</b> have: every built-in module whose
     * {@link ai.tessary.plan.Capability} the flag layer resolves off. The other half of {@link
     * #availableBuiltIns}: that one decides what gets seeded, this one decides what an
     * already-seeded project can still see and sweep.
     *
     * <p>Withholding never writes. A flagged-off built-in's row keeps its {@code enabled} column
     * exactly as the project left it and is suppressed at every read and dispatch instead, which
     * is what makes flipping the flag back on a complete restoration rather than a guess.
     *
     * <p>Fail-closed on an unresolvable project, matching seeding: a project with no org yields
     * every catalog key withheld rather than every catalog key granted.
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
     * {@link #withheldBuiltInKeys} once the org is known: the capability layer's whole answer
     * for one org, which is the part that actually costs anything ({@code
     * CapabilityService#resolve} reads the org's overrides once, cached for ten seconds per org).
     */
    private Set<String> withheldForOrg(String orgId) {
        CapabilityService.CapabilitySet enabled = capabilities.resolve(orgId);
        return catalog.builtIns().stream()
                .filter(b -> !enabled.isEnabled(b.capability()))
                .map(BuiltIn::classifierKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether a stored row reaches its project at all, given {@link #withheldBuiltInKeys}.
     *
     * <p>Two kinds of row are always reachable. A user-authored row ({@code built_in=false}) is
     * not in the catalog, so it has no capability to be gated by. A retired built-in, one whose
     * key has left the catalog entirely, is likewise absent from the withheld set, so its
     * already-disabled row stays listable and its detection history stays readable.
     */
    private static boolean reaches(ClassifierRow row, Set<String> withheldBuiltInKeys) {
        return !row.builtIn() || !withheldBuiltInKeys.contains(row.classifierKey());
    }

    /**
     * Whether one stored classifier reaches this project's org: the boolean form of {@link #get}'s
     * guard, for callers outside this slice that must skip rather than 404.
     *
     * <p>The caller that needs it is alerting: a rule pointing at a classifier the org no longer
     * has must stop evaluating by being passed over rather than by an exception, so one bad rule
     * cannot take the whole heartbeat's rule loop down with it. Returns {@code false} for a
     * classifier id that does not exist, for the same reason.
     */
    public boolean reachesProject(String projectId, String classifierId) {
        return signals.findById(projectId, classifierId)
                .filter(row -> reaches(row, withheldBuiltInKeys(projectId)))
                .isPresent();
    }

    /**
     * The detector kinds whose classifier this org does not have: {@link #withheldBuiltInKeys}
     * keyed the other way, for surfaces that hold a classifier's output rather than the
     * classifier itself.
     *
     * <p>Findings are the case in point: the shared {@code finding} table carries no classifier
     * column, so the only exact way to ask "may this org see this row" is to derive the row's
     * detector and look it up here.
     */
    public Set<String> unavailableDetectorKinds(String projectId) {
        Set<String> withheldKeys = withheldBuiltInKeys(projectId);
        return catalog.builtIns().stream()
                .filter(b -> withheldKeys.contains(b.classifierKey()))
                .map(BuiltIn::detector)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Disable (never delete) enabled {@code built_in=true} rows whose key left the catalog.
     *
     * <p>Keys on catalog membership only, deliberately not {@link #availableBuiltIns}: retirement
     * means "this classifier no longer exists," a flag being off means "this org doesn't have it,"
     * and the two want opposite endings. Narrowing this set by the flag layer would disable a
     * partner's flagged-off rows as though they had been withdrawn, and re-enabling the flag
     * would not bring them back.
     *
     * <p>The flag-off ending is {@link #withheldBuiltInKeys}, a separate method that writes
     * nothing. Withdrawal is the only path here that touches a row, so any future edit that
     * tries to express "flagged off" by disabling a row has to come through this method.
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
     * A call-site fact a detector gates on has landed or changed: rewind every enabled signal
     * that declares it, so the next sweep re-reads history that was scored {@code none()} only
     * because the fact was missing at the time.
     *
     * <p>The traffic that unblocks the plugin's assessment of a repo is, always, traffic these
     * built-ins could not score yet ({@code call_site.output_schema}/{@code call_site.shape} are
     * captured from the repo). Left alone, the sweep cursor moves past it once and never returns,
     * which reads in the product as "nothing malformed" rather than "never checked".
     *
     * <p>Rewinding is whole-signal because the cursor is: one {@code (project, signal)}
     * high-water mark covers every call site. Over-scanning is the deliberate trade: the
     * worker's detection write is idempotent, so a re-sweep re-scores cheaply and writes nothing
     * twice.
     *
     * <p>Disabled signals are rewound too. Re-enabling one does not reset its cursor ({@link
     * ClassifierJobRepository#enqueue} re-pends without disturbing it), so a signal disabled when
     * the fact landed would otherwise resume from its old high-water mark and re-strand the
     * history this exists to recover.
     *
     * @param callSiteIds the call sites that changed, logged for provenance and not used for targeting
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
                // A sweep in flight (or a dead-lettered job) keeps its cursor, so this fact's
                // history stays unscored until something rewinds it again. Silence here would
                // make that indistinguishable from a successful rewind.
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

    /**
     * The project's classifier list as the org may see it: every stored row minus the built-ins
     * the flag layer withholds ({@link #withheldBuiltInKeys}).
     */
    public List<ClassifierRow> list(String projectId) {
        Set<String> withheld = withheldBuiltInKeys(projectId);
        return signals.listByProject(projectId).stream()
                .filter(row -> reaches(row, withheld))
                .toList();
    }

    /**
     * One classifier by id: the tenant + existence guard every per-classifier endpoint funnels
     * through, which is also where a withheld built-in becomes a <b>404</b> rather than a 403.
     * There is no such classifier from this org's point of view, and a "you can't have this"
     * would itself be a mention of a capability the org doesn't have.
     */
    public ClassifierRow get(String projectId, String id) {
        ClassifierRow row =
                signals.findById(projectId, id).orElseThrow(() -> new TessaryException(ClassifierError.NOT_FOUND, id));
        if (!reaches(row, withheldBuiltInKeys(projectId))) {
            throw new TessaryException(ClassifierError.NOT_FOUND, id);
        }
        return row;
    }

    /**
     * Sweep-job health for every signal in the project. One row per signal definition regardless
     * of whether a job has ever been enqueued for it, so a brand-new or disabled signal reads as
     * healthy rather than absent from the response.
     */
    public List<ClassifierDtos.ClassifierHealthView> health(String projectId) {
        Map<String, ClassifierJobRow> byId = jobs.listByProject(projectId).stream()
                .collect(Collectors.toMap(ClassifierJobRow::classifierId, r -> r));
        return list(projectId).stream()
                .map(s -> ClassifierDtos.ClassifierHealthView.of(s.id(), byId.get(s.id()), props.getMaxAttempts()))
                .toList();
    }

    /**
     * Enable or disable a signal definition. Guarded by {@link #get} first, so a withheld
     * built-in 404s instead of being written and then 404ing on the read back.
     */
    public ClassifierRow setEnabled(String projectId, String id, boolean enabled) {
        get(projectId, id); // tenant + existence + capability guard
        if (signals.setEnabled(projectId, id, enabled) == 0) {
            throw new TessaryException(ClassifierError.NOT_FOUND, id);
        }
        return get(projectId, id);
    }

    /**
     * Set the classifier's operating point: {@code discovery} (high recall) or {@code tracking}
     * (high precision). One definition, two modes: the mode filters detections at read time, so
     * switching it never loses history and the differing precision/recall stays surfaceable per mode.
     */
    public ClassifierRow setMode(String projectId, String id, String mode) {
        if (!ClassifierRow.Mode.DISCOVERY.equals(mode) && !ClassifierRow.Mode.TRACKING.equals(mode)) {
            throw new TessaryException(ClassifierError.INVALID_MODE, mode);
        }
        get(projectId, id); // tenant + existence + capability guard, before the write
        if (signals.setMode(projectId, id, mode) == 0) {
            throw new TessaryException(ClassifierError.NOT_FOUND, id);
        }
        return get(projectId, id);
    }

    /**
     * The current window/threshold operating point for a metric-drift classifier: {@link
     * MetricDriftConfig#of} over the classifier's {@code config_json}, which is exactly what {@link
     * MetricDriftSweep} reads on its next pass. Throws {@link ClassifierError#NOT_METRIC_DRIFT} for
     * any other detector: the other six built-ins have no window to tune.
     */
    public ClassifierDtos.TuningView getTuning(String projectId, String id) {
        ClassifierRow signal = requireMetricDrift(projectId, id);
        MetricDriftConfig config = MetricDriftConfig.of(mapper, signal.configJson());
        return ClassifierDtos.TuningView.of(config, impliedFalseAlarmRate(projectId, signal, config));
    }

    /**
     * Tune a metric-drift classifier's operating point. Merges the four edited fields into the
     * classifier's existing parsed config, never a bare four-field blob, so {@code measures} and
     * the other fields an operator hasn't touched survive the write untouched. {@link
     * MetricDriftConfig}'s compact constructor clamps every field to a sane range, so the value
     * read back after a save is the value actually in effect, not necessarily the one submitted.
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
     * What the configured move costs in false alarms on this project's own traffic, or null when
     * nothing has enough traffic to say.
     *
     * <p>The rate a move implies depends on how spread out the traffic is, so it has to be read
     * off the buckets this classifier is actually watching. The median bucket's spread is used
     * rather than the mean: one pathological call site (a cache hit and a cold start sharing a
     * bucket) has a huge sigma, and averaging it in would report a rate no ordinary bucket
     * experiences.
     *
     * <p>Computed against two full windows, the operating point the dial is stated at. A thinner
     * window is held to a higher bar, so it runs quieter than this number, never noisier.
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
                // A sketch on a retired grid, or a blob a newer build wrote. One unreadable baseline must
                // not cost the whole answer.
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

    /** Tenant + existence guard, narrowed to the two detectors {@link MetricDriftConfig} actually governs. */
    private ClassifierRow requireMetricDrift(String projectId, String id) {
        ClassifierRow signal = get(projectId, id);
        if (!BuiltInDetector.Kind.COST_DRIFT.equals(signal.detector())
                && !BuiltInDetector.Kind.DURATION_DRIFT.equals(signal.detector())) {
            throw new TessaryException(ClassifierError.NOT_METRIC_DRIFT, id);
        }
        return signal;
    }

    public List<ClassifierDtos.ClassifierEventView> events(String projectId, int limit) {
        return detections.listByProject(projectId, limit);
    }

    public List<ClassifierDtos.ClassifierEventView> eventsForClassifier(
            String projectId, String classifierId, int limit) {
        return eventsForClassifier(projectId, classifierId, null, limit);
    }

    /**
     * Per-tool tool-call failure rates for the project: the rate-bearing surface, computed live
     * over {@code tool_call} (failed/total grouped by tool name), worst-first. The
     * {@code classifierId} is a tenant + existence guard (the rates are a project-wide property
     * of the raw structured data, so the same numbers back any project's {@code tool_error} signal).
     */
    public List<SubstrateReadRepository.ToolErrorRate> toolErrorRates(String projectId, String classifierId) {
        get(projectId, classifierId); // tenant + existence guard
        return substrate.toolErrorRatesByName(projectId);
    }

    /**
     * Detections for a signal at an operating point: {@code tracking} surfaces only the precise
     * HIGH-confidence subset; {@code discovery} (or {@code null}) surfaces the full high-recall set.
     */
    public List<ClassifierDtos.ClassifierEventView> eventsForClassifier(
            String projectId, String classifierId, @Nullable String mode, int limit) {
        ClassifierRow signal = get(projectId, classifierId); // tenant + existence guard
        // The mode gate is a CONFIDENCE filter over the persisted corpus: tracking surfaces only the
        // precise HIGH band (unbanded NULL reads as high); discovery/null returns everything.
        boolean trackingOnly = ClassifierRow.Mode.TRACKING.equals(mode);
        return detections.listByClassifierKey(projectId, signal.classifierKey(), trackingOnly, limit);
    }

    /**
     * The per-mode detection breakdown for a signal, computed live over the detections for this
     * classifier's key. {@code discovery} fires {@code high + low} (recall); {@code tracking}
     * fires {@code high} only (precision, unbanded NULL reads as high). The {@code low} delta is
     * the recall the weak band buys.
     */
    public ClassifierMetrics metrics(String projectId, String classifierId) {
        ClassifierRow signal = get(projectId, classifierId); // tenant + existence guard
        ClassifierDetectionRepository.ModeCounts c = detections.modeCounts(projectId, signal.classifierKey());
        return new ClassifierMetrics(signal.id(), signal.mode(), c.high() + c.low(), c.high(), c.low());
    }

    /**
     * Per-mode detection counts for one signal. {@code discoveryFired} = every detection;
     * {@code trackingFired} = the HIGH-confidence subset a tracking signal would surface. The gap
     * ({@code lowConfidence}) is the false-positive-tolerant recall discovery adds over tracking.
     */
    public record ClassifierMetrics(
            String classifierId, String mode, long discoveryFired, long trackingFired, long lowConfidence) {}

    /**
     * Per-classifier daily detected-trace volume over the trailing {@code days} window (clamped
     * to 1-30), plus the per-day project trace totals that make the counts a rate. UTC calendar
     * days, oldest first, the last bucket being today-so-far; every array is zero-filled and
     * aligned with {@code days()}, and every classifier definition gets an entry, so a silent
     * classifier shows a flat zero strip rather than a missing row. Counts are distinct traces
     * bucketed by detection time, so a backfill sweep can legitimately push a day's count past
     * that day's trace total.
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

        // list(), not listByProject(): the volume chart is drawn against the classifier list, so a series
        // for a withheld built-in would be a line with no row to name it.
        List<ClassifierDailyCounts> perClassifier = list(projectId).stream()
                .map(s -> new ClassifierDailyCounts(s.id(), countsBySignal.getOrDefault(s.id(), new long[d])))
                .toList();
        return new DailyVolume(dayLabels, traceTotals, perClassifier);
    }

    /** The daily-volume read: aligned, zero-filled arrays over the trailing UTC-day window. */
    public record DailyVolume(List<String> days, long[] traceTotals, List<ClassifierDailyCounts> classifiers) {}

    /** One classifier's distinct-trace detection count per day, aligned with {@link DailyVolume#days()}. */
    public record ClassifierDailyCounts(String classifierId, long[] counts) {}

    /**
     * Ensure a pending sweep job exists for every enabled signal in the project (worker entrypoint).
     *
     * <p>A withheld built-in is not enqueued, so it stops costing anything within a heartbeat of
     * the flag flipping, on existing projects and not only newly created ones. Its
     * {@code enabled} column is untouched, so the flag coming back on resumes exactly the sweep
     * the project had configured.
     */
    public void enqueueEnabled(String projectId) {
        Set<String> withheld = withheldBuiltInKeys(projectId);
        for (ClassifierRow s : signals.listEnabled(projectId)) {
            if (!reaches(s, withheld)) continue;
            jobs.enqueue(projectId, s.id(), props.getDeadLetterCooldownSeconds());
        }
    }
}
