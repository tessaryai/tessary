// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.chart;

import ai.tessary.cases.CaseRow;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.chart.ChartSeries.Availability;
import ai.tessary.classifier.chart.ChartSeries.Facts;
import ai.tessary.classifier.chart.ClassifierChartDtos.CallSiteOption;
import ai.tessary.classifier.chart.ClassifierChartDtos.CaseSpan;
import ai.tessary.classifier.chart.ClassifierChartDtos.CasesView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartBaseline;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartCard;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartChip;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartDay;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartScopesView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ChartsView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ClassifierMenuItem;
import ai.tessary.classifier.chart.ClassifierChartDtos.LearningView;
import ai.tessary.classifier.chart.ClassifierChartDtos.ToolOption;
import ai.tessary.classifier.chart.ClassifierChartRepository.CaseScope;
import ai.tessary.classifier.chart.ClassifierChartRepository.ClassifierOnCallSite;
import ai.tessary.classifier.chart.ClassifierChartRepository.CountRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.DriftBucket;
import ai.tessary.classifier.chart.ClassifierChartRepository.RangeRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.RateRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.ToolCallerRow;
import ai.tessary.classifier.chart.ClassifierChartRepository.ToolErrorTool;
import ai.tessary.classifier.detector.groundedness.GroundednessConfig;
import ai.tessary.classifier.detector.groundedness.GroundednessRateRepository;
import ai.tessary.classifier.detector.groundedness.GroundednessStatus;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRepository.ConfirmedSpan;
import ai.tessary.classifier.frustration.FrustrationConfig;
import ai.tessary.classifier.frustration.FrustrationRateRepository;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository;
import ai.tessary.classifier.metric.MetricBaselineReference;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorBuckets;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository;
import ai.tessary.classifier.toolerror.ToolErrorReferenceRepository.AcceptedReference;
import ai.tessary.classifier.toolerror.ToolErrorRepository;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.worker.ClassifierArming;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The Classifiers page charts: which classifiers have a card for a call site or a tool, each card's daily series,
 * headline, baseline and case strip, and the selectors and menu that choose between them. Reads only; every series
 * comes from tables the detectors already keep.
 */
@Service
public class ClassifierChartService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierChartService.class);

    /** The call-site cards, in page order. User classifiers follow, by name. */
    private static final List<String> CALL_SITE_ORDER = List.of(
            BuiltInDetector.Kind.FRUSTRATION,
            BuiltInDetector.Kind.DURATION_DRIFT,
            BuiltInDetector.Kind.GROUNDEDNESS,
            BuiltInDetector.Kind.COST_DRIFT,
            BuiltInDetector.Kind.MALFORMED_OUTPUT,
            BuiltInDetector.Kind.SECRET_LEAK);

    /** The tool cards, in page order. */
    private static final List<String> TOOL_ORDER =
            List.of(BuiltInDetector.Kind.TOOL_ERROR, BuiltInDetector.Kind.DURATION_DRIFT);

    /** The Configure menu: every built-in, then user classifiers by name. */
    private static final List<String> MENU_ORDER = List.of(
            BuiltInDetector.Kind.FRUSTRATION,
            BuiltInDetector.Kind.DURATION_DRIFT,
            BuiltInDetector.Kind.GROUNDEDNESS,
            BuiltInDetector.Kind.COST_DRIFT,
            BuiltInDetector.Kind.MALFORMED_OUTPUT,
            BuiltInDetector.Kind.SECRET_LEAK,
            BuiltInDetector.Kind.TOOL_ERROR);

    private static final Set<String> RATE_KINDS = Set.of(
            BuiltInDetector.Kind.FRUSTRATION, BuiltInDetector.Kind.GROUNDEDNESS, BuiltInDetector.Kind.MALFORMED_OUTPUT);

    private static final Set<String> DRIFT_KINDS =
            Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);

    /**
     * How far back a rate classifier's replay reads, from its own anchor: the window a learning reference counts
     * its progress over, as the detectors' {@code REPLAY_WINDOW}s are.
     */
    private static final Duration LEARNING_WINDOW = Duration.ofDays(28);

    /**
     * How far before the range a session or trace is grouped from, so one that began before the range keeps its
     * own first day and every range reads the same trials.
     */
    private static final Duration TRIAL_LOOKBACK = Duration.ofDays(28);

    /** How far back a quiet tool card looks for the tool's names: the default trace retention. */
    private static final Duration NAME_LOOKBACK = Duration.ofDays(90);

    private static final String TOOL_PREFIX = ToolErrorBuckets.KIND + ":";

    private final ClassifierService classifiers;
    private final ClassifierChartRepository charts;
    private final FrustrationRateRepository frustrationRates;
    private final GroundednessRateRepository groundednessRates;
    private final GroundednessStatus groundednessStatus;
    private final MalformedOutputRateRepository malformedRates;
    private final ToolErrorRepository toolErrors;
    private final ToolErrorStateRepository toolErrorStates;
    private final ToolErrorReferenceRepository toolErrorReferences;
    private final MetricBaselineRepository baselines;
    private final FindingRepository findings;
    private final ClassifierJobRepository jobs;
    private final SubstrateReadRepository substrate;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public ClassifierChartService(
            ClassifierService classifiers,
            ClassifierChartRepository charts,
            FrustrationRateRepository frustrationRates,
            GroundednessRateRepository groundednessRates,
            GroundednessStatus groundednessStatus,
            MalformedOutputRateRepository malformedRates,
            ToolErrorRepository toolErrors,
            ToolErrorStateRepository toolErrorStates,
            ToolErrorReferenceRepository toolErrorReferences,
            MetricBaselineRepository baselines,
            FindingRepository findings,
            ClassifierJobRepository jobs,
            SubstrateReadRepository substrate,
            ObjectMapper mapper) {
        this(
                classifiers,
                charts,
                frustrationRates,
                groundednessRates,
                groundednessStatus,
                malformedRates,
                toolErrors,
                toolErrorStates,
                toolErrorReferences,
                baselines,
                findings,
                jobs,
                substrate,
                mapper,
                Clock.systemUTC());
    }

    ClassifierChartService(
            ClassifierService classifiers,
            ClassifierChartRepository charts,
            FrustrationRateRepository frustrationRates,
            GroundednessRateRepository groundednessRates,
            GroundednessStatus groundednessStatus,
            MalformedOutputRateRepository malformedRates,
            ToolErrorRepository toolErrors,
            ToolErrorStateRepository toolErrorStates,
            ToolErrorReferenceRepository toolErrorReferences,
            MetricBaselineRepository baselines,
            FindingRepository findings,
            ClassifierJobRepository jobs,
            SubstrateReadRepository substrate,
            ObjectMapper mapper,
            Clock clock) {
        this.classifiers = classifiers;
        this.charts = charts;
        this.frustrationRates = frustrationRates;
        this.groundednessRates = groundednessRates;
        this.groundednessStatus = groundednessStatus;
        this.malformedRates = malformedRates;
        this.toolErrors = toolErrors;
        this.toolErrorStates = toolErrorStates;
        this.toolErrorReferences = toolErrorReferences;
        this.baselines = baselines;
        this.findings = findings;
        this.jobs = jobs;
        this.substrate = substrate;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---- the range ----------------------------------------------------------------------------------

    /** The UTC days a request covers. */
    private record Range(int days, LocalDate today, LocalDate from) {

        Instant fromAt() {
            return from.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant toExclusive() {
            return today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant groupFrom() {
            return fromAt().minus(TRIAL_LOOKBACK);
        }

        Instant headFrom() {
            return ChartSeries.fromDay(today, ChartSeries.HEADLINE_DAYS)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
    }

    private Range range(int days) {
        if (!ChartSeries.RANGES.contains(days)) {
            throw new TessaryException(ClassifierError.INVALID_CHART_DAYS, days);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return new Range(days, today, ChartSeries.fromDay(today, days));
    }

    // ---- what one request knows about the project's classifiers -------------------------------------

    /**
     * The project's rows and the facts their on/off rules read, gathered once per request. Lazily, because a tool
     * page reads none of the call-site facts.
     */
    private final class Reads {

        final String id;
        final Range range;
        final List<ClassifierRow> rows;
        final List<String> knownCallSites;
        private @Nullable Set<String> schemaCallSites;
        private final Map<String, Facts> facts = new HashMap<>();
        private final Map<String, Map<String, CarriedState>> states = new HashMap<>();
        private final Map<String, List<MetricBaselineRow>> driftRows = new HashMap<>();
        private @Nullable Map<String, List<ConfirmedSpan>> confirmed;
        private @Nullable Map<String, AcceptedReference> references;

        Reads(String id, Range range) {
            this.id = id;
            this.range = range;
            this.rows = classifiers.list(id);
            this.knownCallSites = classifiers.knownCallSiteIds(id);
        }

        Set<String> schemaCallSites() {
            if (schemaCallSites == null) {
                schemaCallSites = substrate
                        .callSiteOutputSchemas(id, new HashSet<>(knownCallSites))
                        .keySet();
            }
            return schemaCallSites;
        }

        Facts facts(ClassifierRow row) {
            return facts.computeIfAbsent(row.id(), k -> {
                Set<String> measures = DRIFT_KINDS.contains(row.detector())
                        ? new HashSet<>(
                                MetricDriftConfig.of(mapper, row.configJson()).measures())
                        : Set.of();
                Set<String> schemas =
                        BuiltInDetector.Kind.MALFORMED_OUTPUT.equals(row.detector()) ? schemaCallSites() : Set.of();
                return new Facts(waitingReason(row), schemas, measures);
            });
        }

        /** Why the row as a whole cannot judge: a pause, or Groundedness not scoring. */
        private @Nullable String waitingReason(ClassifierRow row) {
            if (!row.enabled()) return null;
            if (BuiltInDetector.Kind.GROUNDEDNESS.equals(row.detector())) {
                String state = groundednessStatus.view(id, row).state();
                return GroundednessStatus.State.NOT_SCORING.wire().equals(state)
                                || GroundednessStatus.State.NOT_SET_UP.wire().equals(state)
                        ? state
                        : null;
            }
            if (BuiltInDetector.Kind.MALFORMED_OUTPUT.equals(row.detector())) return null;
            return classifiers.readiness(id, row);
        }

        Map<String, CarriedState> states(String detector) {
            return states.computeIfAbsent(detector, d -> switch (d) {
                case BuiltInDetector.Kind.FRUSTRATION ->
                    frustrationRates.states().byTool(id);
                case BuiltInDetector.Kind.GROUNDEDNESS ->
                    groundednessRates.states().byTool(id);
                case BuiltInDetector.Kind.MALFORMED_OUTPUT ->
                    malformedRates.states().byTool(id);
                default -> toolErrorStates.byTool(id);
            });
        }

        Map<String, AcceptedReference> references() {
            if (references == null) references = toolErrorReferences.byTool(id);
            return references;
        }

        List<MetricBaselineRow> driftRows(ClassifierRow row) {
            return driftRows.computeIfAbsent(row.id(), k -> baselines.listByClassifier(id, row.id()));
        }

        Map<String, List<ConfirmedSpan>> confirmed() {
            if (confirmed == null) {
                confirmed = findings.confirmedSpansBySubject(id, List.copyOf(DRIFT_KINDS));
            }
            return confirmed;
        }

        List<ClassifierRow> ordered(List<String> builtIns, boolean withUsers) {
            List<ClassifierRow> out = new ArrayList<>();
            for (String kind : builtIns) {
                rows.stream()
                        .filter(r -> r.builtIn() && kind.equals(r.detector()))
                        .findFirst()
                        .ifPresent(out::add);
            }
            if (withUsers) {
                rows.stream()
                        .filter(r -> BuiltInDetector.Kind.REGEX.equals(r.detector()))
                        .sorted(Comparator.comparing(ClassifierRow::name).thenComparing(ClassifierRow::id))
                        .forEach(out::add);
            }
            return out;
        }
    }

    // ---- GET /chart-scopes --------------------------------------------------------------------------

    /** Every call site and tool the selectors offer, and every classifier the Configure menu lists. */
    public ChartScopesView scopes(String projectId, int days) {
        Reads p = new Reads(projectId, range(days));
        Instant from = p.range.fromAt();

        List<ClassifierRow> callSiteRows = p.ordered(CALL_SITE_ORDER, true);
        Set<String> callSiteKeys = new TreeSet<>();
        for (ClassifierRow row : callSiteRows) {
            if (!DRIFT_KINDS.contains(row.detector())) callSiteKeys.add(row.classifierKey());
        }
        Map<String, Integer> openByCallSite = charts.openCasesByCallSite(projectId, callSiteKeys);
        Map<String, Long> turns = charts.turnsByCallSite(projectId, from);
        List<CallSiteOption> callSites = new ArrayList<>();
        for (String cs : p.knownCallSites) {
            if (ClassifierChartRepository.UNATTRIBUTED.equals(cs)) continue;
            int open = openByCallSite.getOrDefault(cs, 0);
            callSites.add(new CallSiteOption(
                    cs, open, open == 0 && newAndLearning(p, callSiteRows, cs), turns.getOrDefault(cs, 0L)));
        }

        Map<String, Integer> openByTool = charts.openCasesByTool(projectId);
        Map<String, long[]> calls = new TreeMap<>();
        Map<String, Set<String>> callers = new HashMap<>();
        for (ToolCallerRow r : charts.toolCallers(projectId, from)) {
            String key = ToolErrorBuckets.toolKey(r.name());
            calls.computeIfAbsent(key, k -> new long[1])[0] += r.calls();
            Set<String> who = callers.computeIfAbsent(key, k -> new TreeSet<>());
            if (r.callSiteId() != null && !ClassifierChartRepository.UNATTRIBUTED.equals(r.callSiteId())) {
                who.add(r.callSiteId());
            }
        }
        for (String key : openByTool.keySet()) calls.computeIfAbsent(key, k -> new long[1]);
        List<ToolOption> tools = new ArrayList<>();
        for (Map.Entry<String, long[]> e : calls.entrySet()) {
            String key = e.getKey();
            tools.add(new ToolOption(
                    key,
                    key.startsWith(TOOL_PREFIX) ? key.substring(TOOL_PREFIX.length()) : key,
                    openByTool.getOrDefault(key, 0),
                    e.getValue()[0],
                    List.copyOf(callers.getOrDefault(key, Set.of()))));
        }

        List<ClassifierMenuItem> menu = new ArrayList<>();
        for (ClassifierRow row : p.ordered(MENU_ORDER, true)) menu.add(menuItem(p, row));
        return new ChartScopesView(p.range.days(), callSites, tools, menu);
    }

    /**
     * A call site reads "New, learning" when at least one rate or drift classifier is on for it and every one that
     * is on is still learning. A waiting classifier (Malformed Output with no schema) judges nothing, so it does not
     * count.
     */
    private boolean newAndLearning(Reads p, List<ClassifierRow> rows, String cs) {
        boolean any = false;
        for (ClassifierRow row : rows) {
            boolean rate = RATE_KINDS.contains(row.detector());
            if (!rate && !DRIFT_KINDS.contains(row.detector())) continue;
            if (!ChartSeries.Availability.ON.equals(
                    ChartSeries.forCallSite(row, cs, p.facts(row)).state())) continue;
            any = true;
            boolean learning = rate
                    ? ChartSeries.rateBaseline(
                                    p.states(row.detector()).get(cs), null, ChartSeries.minBaseline(row, mapper))
                            == null
                    : driftReference(p, row, callSiteMeasure(row), BucketKind.CALL_SITE, cs)
                                    .baseline()
                            == null;
            if (!learning) return false;
        }
        return any;
    }

    private ClassifierMenuItem menuItem(Reads p, ClassifierRow row) {
        String detector = row.detector();
        String covers;
        if (BuiltInDetector.Kind.TOOL_ERROR.equals(detector)) {
            covers = "tools";
        } else if (BuiltInDetector.Kind.DURATION_DRIFT.equals(detector)) {
            Set<String> measures = p.facts(row).measures();
            boolean turn = measures.contains(Measure.TURN_DURATION);
            boolean tool = measures.contains(Measure.TOOL_DURATION);
            covers = turn && tool ? "call_sites_and_tools" : tool ? "tools" : "call_sites";
        } else {
            covers = "call_sites";
        }
        int count = 0;
        if (!"tools".equals(covers)) {
            for (String cs : p.knownCallSites) {
                if (ChartSeries.forCallSite(row, cs, p.facts(row)).on()) count++;
            }
        }
        String status;
        String reason = null;
        if (!row.enabled()) {
            status = Availability.OFF;
        } else {
            reason = p.facts(row).waitingReason();
            if (reason == null && BuiltInDetector.Kind.MALFORMED_OUTPUT.equals(detector)) {
                boolean anySchema = p.knownCallSites.stream()
                        .anyMatch(cs -> row.runsOn(cs) && p.schemaCallSites().contains(cs));
                if (!anySchema) reason = ChartSeries.NO_SCHEMA;
            }
            status = reason == null ? Availability.ON : Availability.WAITING;
        }
        boolean all = row.callSiteIds() == null;
        return new ClassifierMenuItem(row.id(), row.classifierKey(), row.name(), status, reason, covers, all, count);
    }

    // ---- GET /charts --------------------------------------------------------------------------------

    /** The cards and chips of one call site or one tool. Exactly one of the two is set. */
    public ChartsView charts(String projectId, @Nullable String callSiteId, @Nullable String toolKey, int days) {
        boolean bySite = callSiteId != null && !callSiteId.isBlank();
        boolean byTool = toolKey != null && !toolKey.isBlank();
        if (bySite == byTool) throw new TessaryException(ClassifierError.CHART_SCOPE);
        Reads p = new Reads(projectId, range(days));
        List<ChartCard> cards = new ArrayList<>();
        List<ChartChip> chips = new ArrayList<>();
        if (bySite) {
            String cs = Objects.requireNonNull(callSiteId);
            if (!p.knownCallSites.contains(cs)) throw new TessaryException(ClassifierError.UNKNOWN_CALL_SITE, cs);
            for (ClassifierRow row : p.ordered(CALL_SITE_ORDER, true)) {
                Availability a = ChartSeries.forCallSite(row, cs, p.facts(row));
                place(row, a, () -> callSiteCard(p, row, cs), p.range.fromAt(), cards, chips);
            }
        } else {
            String key = Objects.requireNonNull(toolKey);
            for (ClassifierRow row : p.ordered(TOOL_ORDER, false)) {
                Availability a = ChartSeries.forTool(row, p.facts(row).measures());
                place(row, a, () -> toolCard(p, row, key), p.range.fromAt(), cards, chips);
            }
        }
        return new ChartsView(
                bySite ? "call_site" : "tool",
                bySite ? Objects.requireNonNull(callSiteId) : Objects.requireNonNull(toolKey),
                p.range.days(),
                p.range.from().toString(),
                p.range.today().toString(),
                cards,
                chips);
    }

    /** A card, and the day before the range it last had data when it has none in it. */
    private record Built(ChartCard card, Function<Instant, Optional<LocalDate>> lastDay) {}

    private interface CardSupplier {
        Built get();
    }

    private static void place(
            ClassifierRow row,
            Availability a,
            CardSupplier build,
            Instant from,
            List<ChartCard> cards,
            List<ChartChip> chips) {
        if (Availability.OFF.equals(a.state()) || Availability.WAITING.equals(a.state())) {
            chips.add(new ChartChip(row.id(), row.classifierKey(), row.name(), a.state(), a.reason(), null));
            return;
        }
        Built built = build.get();
        ChartCard card = built.card();
        if (card.learning() == null && !ChartSeries.hasData(card.kind(), card.days())) {
            LocalDate since = built.lastDay().apply(from).orElse(null);
            chips.add(new ChartChip(
                    row.id(), row.classifierKey(), row.name(), "quiet", null, since == null ? null : since.toString()));
            return;
        }
        cards.add(card);
    }

    private Built callSiteCard(Reads p, ClassifierRow row, String cs) {
        return switch (row.detector()) {
            case BuiltInDetector.Kind.FRUSTRATION, BuiltInDetector.Kind.GROUNDEDNESS -> scoredRateCard(p, row, cs);
            case BuiltInDetector.Kind.MALFORMED_OUTPUT -> malformedCard(p, row, cs);
            case BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT -> callSiteRangeCard(p, row, cs);
            default -> countCard(p, row, cs);
        };
    }

    private Built toolCard(Reads p, ClassifierRow row, String key) {
        return BuiltInDetector.Kind.TOOL_ERROR.equals(row.detector())
                ? toolErrorCard(p, row, key)
                : toolDurationCard(p, row, key);
    }

    // ---- rate cards ---------------------------------------------------------------------------------

    private Built scoredRateCard(Reads p, ClassifierRow row, String cs) {
        Instant started = Instant.now();
        boolean frustration = BuiltInDetector.Kind.FRUSTRATION.equals(row.detector());
        String scorerVersion = frustration
                ? FrustrationConfig.of(mapper, row.configJson()).scorerVersion()
                : GroundednessConfig.of(mapper, row.configJson()).scorerVersion();
        Range r = p.range;
        List<RateRow> rows = frustration
                ? charts.frustrationDays(p.id, row.id(), scorerVersion, cs, r.groupFrom(), r.fromAt())
                : charts.groundednessDays(p.id, row.id(), scorerVersion, cs, r.groupFrom(), r.fromAt());
        ChartBaseline baseline =
                ChartSeries.rateBaseline(p.states(row.detector()).get(cs), null, ChartSeries.minBaseline(row, mapper));
        LearningView learning = baseline != null
                ? null
                : rateLearning(p, row, cs, () -> {
                    Optional<Instant> newest = frustration
                            ? frustrationRates.newestTurnAt(p.id, row.id(), scorerVersion)
                            : groundednessRates.newestObservationAt(p.id, row.id(), scorerVersion);
                    return newest.map(n -> frustration
                                    ? frustrationRates.hourlyTallies(
                                            p.id, row.id(), scorerVersion, n.minus(LEARNING_WINDOW))
                                    : groundednessRates.hourlyTallies(
                                            p.id, row.id(), scorerVersion, n.minus(LEARNING_WINDOW)))
                            .orElse(List.of());
                });
        ChartCard card = rateCard(row, rows, baseline, learning, new ClassifierOnCallSite(row.classifierKey(), cs), p);
        logRead(p, row, "call_site", rows.size(), started);
        return new Built(
                card,
                before -> frustration
                        ? charts.frustrationLastDay(p.id, row.id(), scorerVersion, cs, before)
                        : charts.groundednessLastDay(p.id, row.id(), scorerVersion, cs, before));
    }

    private Built malformedCard(Reads p, ClassifierRow row, String cs) {
        Instant started = Instant.now();
        String cursor = jobs.findByClassifier(p.id, row.id())
                .map(ClassifierJobRow::cursorAt)
                .orElse(null);
        List<RateRow> rows =
                cursor == null ? List.of() : charts.malformedDays(p.id, row.id(), cs, p.range.fromAt(), cursor);
        ChartBaseline baseline =
                ChartSeries.rateBaseline(p.states(row.detector()).get(cs), null, ChartSeries.minBaseline(row, mapper));
        LearningView learning = baseline != null
                ? null
                : rateLearning(
                        p,
                        row,
                        cs,
                        () -> cursor == null
                                ? List.of()
                                : malformedRates.hourlyTallies(
                                        p.id, row.id(), clock.instant().minus(LEARNING_WINDOW), cursor));
        ChartCard card = rateCard(row, rows, baseline, learning, new ClassifierOnCallSite(row.classifierKey(), cs), p);
        logRead(p, row, "call_site", rows.size(), started);
        return new Built(
                card, before -> cursor == null ? Optional.empty() : charts.malformedLastDay(p.id, cs, before, cursor));
    }

    private Built toolErrorCard(Reads p, ClassifierRow row, String key) {
        Instant started = Instant.now();
        List<String> names = toolErrors.namesByToolKey(p.id, p.range.fromAt()).getOrDefault(key, List.of());
        List<RateRow> rows = charts.toolErrorDays(p.id, names, p.range.fromAt());
        ChartBaseline baseline = ChartSeries.rateBaseline(
                p.states(row.detector()).get(key), p.references().get(key), ChartSeries.minBaseline(row, mapper));
        LearningView learning = baseline != null
                ? null
                : rateLearning(
                        p,
                        row,
                        key,
                        () -> toolErrors
                                .newestEventAt(p.id)
                                .map(n -> toolErrors.hourlyTallies(p.id, n.minus(LEARNING_WINDOW)))
                                .orElse(List.of()));
        ChartCard card = rateCard(row, rows, baseline, learning, new ToolErrorTool(key), p);
        logRead(p, row, "tool", rows.size(), started);
        return new Built(card, before -> {
            List<String> older =
                    toolErrors.namesByToolKey(p.id, before.minus(NAME_LOOKBACK)).getOrDefault(key, List.of());
            return charts.toolCallLastDay(p.id, older, before);
        });
    }

    private interface Tallies {
        List<HourlyToolTally> read();
    }

    /** How far {@code key} has learned, over its detector's own replay window, against the minimum. */
    private LearningView rateLearning(Reads p, ClassifierRow row, String key, Tallies tallies) {
        List<HourlyToolTally> mine =
                tallies.read().stream().filter(t -> key.equals(t.toolKey())).toList();
        return new LearningView(
                CarriedState.learned(p.states(row.detector()).get(key), mine), ChartSeries.minBaseline(row, mapper));
    }

    private ChartCard rateCard(
            ClassifierRow row,
            List<RateRow> rows,
            @Nullable ChartBaseline baseline,
            @Nullable LearningView learning,
            CaseScope scope,
            Reads p) {
        Map<LocalDate, ChartDay> byDay = new HashMap<>();
        for (RateRow r : rows) byDay.put(r.day(), ChartDay.rate(r.day().toString(), r.checked(), r.flagged()));
        List<ChartDay> days = ChartSeries.dense(p.range.from(), p.range.today(), byDay, ChartSeries::emptyRate);
        return new ChartCard(
                row.id(),
                row.classifierKey(),
                row.name(),
                ChartCard.RATE,
                null,
                "fraction",
                learning,
                ChartSeries.rateHeadline(days, baseline),
                baseline,
                null,
                days,
                cases(p, scope));
    }

    // ---- range cards --------------------------------------------------------------------------------

    private static String callSiteMeasure(ClassifierRow row) {
        return BuiltInDetector.Kind.COST_DRIFT.equals(row.detector()) ? Measure.COST : Measure.TURN_DURATION;
    }

    private Built callSiteRangeCard(Reads p, ClassifierRow row, String cs) {
        Instant started = Instant.now();
        String measure = callSiteMeasure(row);
        boolean cost = Measure.COST.equals(measure);
        Range r = p.range;
        List<RangeRow> rows =
                cost ? charts.costDays(p.id, cs, r.fromAt()) : charts.turnDurationDays(p.id, cs, r.fromAt());
        Double pooled = cost ? charts.costP95(p.id, cs, r.headFrom()) : charts.turnDurationP95(p.id, cs, r.headFrom());
        DriftReference ref = driftReference(p, row, measure, BucketKind.CALL_SITE, cs);
        ChartCard card = rangeCard(
                row,
                measure,
                cost ? "usd" : "ms",
                rows,
                pooled,
                ref,
                new DriftBucket(measure, BucketKind.CALL_SITE, cs),
                p);
        logRead(p, row, "call_site", rows.size(), started);
        return new Built(card, before -> charts.turnLastDay(p.id, cs, before, cost));
    }

    private Built toolDurationCard(Reads p, ClassifierRow row, String key) {
        Instant started = Instant.now();
        Range r = p.range;
        List<String> names = toolSpanNames(p, key, r.fromAt());
        List<String> scope = row.callSiteIds();
        List<RangeRow> rows = charts.toolDurationDays(p.id, names, scope, r.fromAt());
        Double pooled = charts.toolDurationP95(p.id, names, scope, r.headFrom());
        DriftReference ref = driftReference(p, row, Measure.TOOL_DURATION, BucketKind.TOOL, key);
        ChartCard card = rangeCard(
                row,
                Measure.TOOL_DURATION,
                "ms",
                rows,
                pooled,
                ref,
                new DriftBucket(Measure.TOOL_DURATION, BucketKind.TOOL, key),
                p);
        logRead(p, row, "tool", rows.size(), started);
        return new Built(card, before -> {
            Instant lookback = before.minus(NAME_LOOKBACK);
            return charts.toolDurationLastDay(p.id, toolSpanNames(p, key, lookback), scope, lookback, before);
        });
    }

    /** The raw tool span names since {@code from} that fold to {@code key}. */
    private List<String> toolSpanNames(Reads p, String key, Instant from) {
        return charts.toolSpanNames(p.id, from).stream()
                .filter(name -> key.equals(ToolErrorBuckets.toolKey(name)))
                .toList();
    }

    /** A drift bucket's reference, or how far it has learned towards one. Exactly one is set. */
    private record DriftReference(
            @Nullable ChartBaseline baseline, @Nullable LearningView learning) {}

    /**
     * The bucket's reference while it is armed and has a sketch on the live grid. A bucket with no row, one still
     * learning, and an armed one whose every sketch is on a dead grid are all learning: the detector re-pins the
     * last on its next close.
     */
    private DriftReference driftReference(Reads p, ClassifierRow row, String measure, String bucketKind, String key) {
        MetricDriftConfig config = MetricDriftConfig.of(mapper, row.configJson());
        long needed = config.minSample();
        MetricBaselineRow bucket = p.driftRows(row).stream()
                .filter(b ->
                        measure.equals(b.measure()) && bucketKind.equals(b.bucketKind()) && key.equals(b.bucketKey()))
                .findFirst()
                .orElse(null);
        if (bucket == null) return new DriftReference(null, new LearningView(0, needed));
        LearningView learning = new LearningView(bucket.currentCount(), needed);
        if (MetricBaselineRow.State.LEARNING.equals(bucket.state())) return new DriftReference(null, learning);
        return config.measured().stream()
                .filter(m -> measure.equals(m.measure()))
                .findFirst()
                .flatMap(m -> MetricBaselineReference.quantiles(
                        bucket,
                        m.grid(),
                        p.range.today().toString(),
                        MetricBaselineReference.excludedDays(p.confirmed().get(bucket.id()))))
                .map(q -> new DriftReference(ChartBaseline.range(q.p50(), q.p95()), null))
                .orElse(new DriftReference(null, learning));
    }

    private ChartCard rangeCard(
            ClassifierRow row,
            String measure,
            String unit,
            List<RangeRow> rows,
            @Nullable Double pooledP95,
            DriftReference ref,
            CaseScope scope,
            Reads p) {
        Map<LocalDate, ChartDay> byDay = new HashMap<>();
        for (RangeRow r : rows) byDay.put(r.day(), ChartDay.range(r.day().toString(), r.n(), r.p50(), r.p95()));
        List<ChartDay> days = ChartSeries.dense(p.range.from(), p.range.today(), byDay, ChartSeries::emptyRange);
        return new ChartCard(
                row.id(),
                row.classifierKey(),
                row.name(),
                ChartCard.RANGE,
                measure,
                unit,
                ref.learning(),
                ChartSeries.rangeHeadline(pooledP95, ref.baseline()),
                ref.baseline(),
                null,
                days,
                cases(p, scope));
    }

    // ---- count cards --------------------------------------------------------------------------------

    /**
     * Secret Leak and user classifiers. Each day's {@code count} is what the arming bar counts: the busiest facet on
     * this call site for a faceted bar, the whole project for a whole-project one. {@code total} stays this call
     * site's detections.
     */
    private Built countCard(Reads p, ClassifierRow row, String cs) {
        Instant started = Instant.now();
        ClassifierArming.Config arming = ClassifierArming.parse(mapper, row.configJson());
        boolean highOnly = arming != null && arming.highOnly(row);
        String facet = ClassifierArming.facetKey(row.detector());
        Instant from = p.range.fromAt();
        List<CountRow> rows = charts.countDays(row.detector(), p.id, row.id(), cs, from, highOnly, facet);
        Map<LocalDate, CountRow> mine = new HashMap<>();
        for (CountRow r : rows) mine.put(r.day(), r);
        Map<LocalDate, CountRow> counted = arming == null || facet != null
                ? mine
                : charts.projectCountDays(row.detector(), p.id, row.id(), from, highOnly);
        Set<LocalDate> dates = new LinkedHashSet<>(mine.keySet());
        dates.addAll(counted.keySet());
        Map<LocalDate, ChartDay> byDay = new HashMap<>();
        for (LocalDate d : dates) {
            CountRow own = mine.get(d);
            CountRow bar = counted.get(d);
            byDay.put(
                    d,
                    ChartSeries.countDay(
                            d.toString(),
                            bar == null ? 0 : bar.events(),
                            bar == null ? 0 : bar.sessions(),
                            own == null ? 0 : own.total(),
                            arming));
        }
        List<ChartDay> days = ChartSeries.dense(p.range.from(), p.range.today(), byDay, ChartSeries::emptyCount);
        ChartCard card = new ChartCard(
                row.id(),
                row.classifierKey(),
                row.name(),
                ChartCard.COUNT,
                null,
                "count",
                null,
                ChartSeries.countHeadline(days),
                null,
                ChartSeries.armingView(arming, row),
                days,
                cases(p, new ClassifierOnCallSite(row.classifierKey(), cs)));
        logRead(p, row, "call_site", rows.size(), started);
        return new Built(card, before -> charts.countLastDay(row.detector(), p.id, row.id(), cs, before));
    }

    // ---- cases --------------------------------------------------------------------------------------

    private CasesView cases(Reads p, CaseScope scope) {
        List<CaseSpan> spans = charts.caseSpans(p.id, scope, p.range.fromAt(), p.range.toExclusive());
        Set<String> open = new HashSet<>();
        for (CaseSpan s : spans) {
            if (CaseRow.State.OPEN.equals(s.caseState())) open.add(s.caseId());
        }
        return new CasesView(open.size(), spans);
    }

    private static void logRead(Reads p, ClassifierRow row, String scope, int rows, Instant started) {
        StructuredLog.info(log, Markers.OPS, "classifier.chart.read")
                .message(
                        "read %d day row(s) of %s for a %d-day %s chart",
                        rows, row.classifierKey(), p.range.days(), scope)
                .field("project", p.id)
                .field("card", row.classifierKey())
                .field("scope", scope)
                .field("days", p.range.days())
                .field("rows", rows)
                .durationMs(started)
                .log();
    }
}
