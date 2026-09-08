// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.cases;

import ai.tessary.evals.classifier.ClassifierRepository;
import ai.tessary.evals.classifier.ClassifierRow;
import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.finding.FindingRepository;
import ai.tessary.evals.classifier.finding.FindingRepository.SurvivalGate;
import ai.tessary.evals.classifier.finding.FindingRow;
import ai.tessary.evals.classifier.finding.FindingTitle;
import ai.tessary.evals.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.evals.classifier.metric.MetricDriftConfig;
import ai.tessary.evals.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.evals.classifier.metric.MetricFindingEvidence;
import ai.tessary.evals.classifier.metric.MetricFindingEvidence.Read;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Metric drift, from the findings the classifier slice maintains — one bucket's duration or cost
 * distribution sitting measurably away from its own earlier one
 * ({@code classifiers/metric_drift/PROGRAM.md} §8.1).
 *
 * <p><b>Which findings become cases: only the triaged ones.</b> Layer 1 here detects <em>change</em>
 * and cannot tell change from a problem — slow is not bad and expensive is not bad, a forty-second
 * research run and a $0.40 turn are both routinely correct — so its findings are leads on the
 * Classifiers page, written with <b>no alert budget at all</b> so the operating point can be tuned
 * against real firings rather than a guessed number (§9). That decision is only survivable because of
 * this gate: a case opens when a triage run ruled the shift's claim {@code positive}, or when a human pressed
 * <em>Real deviation</em> on it directly. Triage is the screen people get paged from, and it sees that
 * subset and nothing else. Same posture as {@link BehaviorDriftSource}, which
 * {@link CaseRow.Detector#BEHAVIOR_DRIFT} already documents as "a finding that survived triage".
 *
 * <p><b>Which of the two authorities ruled is part of the case, not a footnote.</b> A human press and a
 * triage run are claims of different strength, so {@link #gateSentence} names which one the basis rests
 * on rather than letting a reader assume the stronger (launch requirement B7). There is no lane to name
 * beside it any more: triage opens no repository, so every machine ruling is made on the same terms.
 *
 * <p><b>The live set, not a delta.</b> {@link #detect} returns everything currently firing every pass
 * and {@link CaseReconciler} closes the cases whose detections dropped out. That maps cleanly onto what
 * recovery looks like here: nothing writes "this bucket came back", because a population that returns to
 * its reference simply stops earning shifted windows — so its finding stops being bumped, falls out of
 * the query below, and its case closes itself. The two ways a case ends without anyone touching Triage
 * are that, and pressing <em>Legitimate — absorb</em>, which re-pins the reference and closes the
 * finding.
 *
 * <p><b>What this source never does is invent a number.</b> The title, the basis and the value triple
 * are read back out of the finding's own evidence blob through {@link MetricFindingEvidence#read}, so a
 * case and the finding it came from cannot disagree about how much slower something got. When that blob
 * cannot be read the case is still opened — it is a confirmed regression and dropping it would be the
 * worst possible failure mode — but it says the numbers are unavailable rather than showing invented
 * ones.
 */
@Component
public class MetricDriftSource implements CaseSource {

    private static final Logger log = LoggerFactory.getLogger(MetricDriftSource.class);

    /** The detectors whose signals carry a {@link MetricDriftConfig}, and so a window horizon. */
    private static final Set<String> METRIC_DETECTORS =
            Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);

    /**
     * The size of the live set this will read. Not a page size — a truncated live set would read
     * downstream as a recovery and close cases that are still firing, so this is set far past any
     * plausible number of simultaneously-triaged shifts in one project rather than at a display
     * limit. If it is ever hit, the right response is to find out why a project has a thousand confirmed
     * distribution shifts, not to raise it.
     */
    private static final int LIVE_SET_CAP = 1_000;

    /**
     * Where severity saturates: {@code ln(3)}, a 3× move. Ordering only, like every severity in this
     * slice — past a tripling, "more" stops changing what a human should do first, and the honest
     * magnitude is in {@link CaseDetection#basis} and the value triple beside it.
     */
    private static final double SEVERITY_SATURATION = Math.log(3);

    /**
     * Severity for a finding whose evidence could not be read. Mid-list deliberately: an unreadable blob
     * is not evidence of a small move, and sorting a human-confirmed regression to the bottom because a
     * JSON column was malformed would hide it exactly as effectively as dropping it.
     */
    private static final double UNKNOWN_SEVERITY = 0.5;

    private final FindingRepository findings;
    private final ClassifierRepository signals;
    private final ObjectMapper mapper;

    /**
     * The two classifiers whose findings open a metric-drift case. Named rather than derived from a
     * shared {@code distribution_shift} cause kind: {@code classifier_key} is the column every reader of
     * the shared table routes on, and duration and cost are separately armable.
     */
    private static final List<String> METRIC_CLASSIFIERS =
            List.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT);

    public MetricDriftSource(FindingRepository findings, ClassifierRepository signals, ObjectMapper mapper) {
        this.findings = findings;
        this.signals = signals;
        this.mapper = mapper;
    }

    @Override
    public String detector() {
        return CaseRow.Detector.METRIC_DRIFT;
    }

    @Override
    public List<CaseDetection> detect(String projectId) {
        String seenSince = Instant.now().minus(quietWindow(projectId)).toString();
        List<CaseDetection> out = new ArrayList<>();
        for (FindingRow finding : findings.listSurvivingAnalysis(
                projectId, METRIC_CLASSIFIERS, SurvivalGate.MACHINE_OR_HUMAN, seenSince, LIVE_SET_CAP)) {
            out.add(toDetection(finding));
        }
        return out;
    }

    /**
     * How long a confirmed shift may go without firing before its case counts as recovered: the longest
     * window any of this project's metric-drift classifiers is configured to close.
     *
     * <p>Derived rather than constant because it is not a judgement about how long a human should wait —
     * it is the detector's own clock. A bucket thin enough to close windows weekly (the default
     * {@code window_max_hours} of 168) fires at most weekly even while it is regressing, so a shorter
     * horizon would close its case between two consecutive firings and reopen it on the next; a bucket
     * thick enough to close windows hourly refreshes hourly and recovers within one horizon of stopping.
     * Reading the project's own configuration is what keeps those two cases consistent when someone
     * moves the dial.
     *
     * <p>The cost of a horizon this long is recovery LATENCY — a bucket that came back still shows a
     * case until its window would have closed. The sharper fix is for the sweep to resolve the finding
     * when a bucket's comparison goes quiet, which is a positive signal rather than an absence; that is
     * a follow-up, and this is the honest bound on what today's data can say.
     */
    private Duration quietWindow(String projectId) {
        int hours = 0;
        for (ClassifierRow signal : signals.listByProject(projectId)) {
            if (!METRIC_DETECTORS.contains(signal.detector())) continue;
            hours = Math.max(
                    hours, MetricDriftConfig.of(mapper, signal.configJson()).windowMaxHours());
        }
        return Duration.ofHours(hours > 0 ? hours : MetricDriftConfig.DEFAULT_WINDOW_MAX_HOURS);
    }

    private static CaseDetection toDetection(FindingRow finding) {
        Read read = MetricFindingEvidence.read(finding.payloadJson());
        if (read == null) {
            log.warn(
                    "metric-drift finding {} has unreadable evidence — opening its case without numbers", finding.id());
        }
        // The CAUSE is the subject: one case per cause, exactly as the finding is one row per cause, so
        // escalating a finding into Triage never re-cuts what the row is about. The cause key already
        // carries the measure, the bucket, the direction and which reference moved — a shift against last
        // week and a creep since the last deploy are two claims about one bucket and stay two cases,
        // because absorbing one of them is not a statement about the other.
        CaseKey key = new CaseKey(
                CaseRow.Detector.METRIC_DRIFT,
                CaseRow.SubjectKind.METRIC_BASELINE,
                finding.nativeCauseKey(),
                measureOf(finding));
        return new CaseDetection(
                key,
                read != null ? read.bucketKey() : finding.nativeCauseKey(),
                finding.callSiteId(),
                finding.id(),
                // The SAME sentence the Classifiers page renders on the finding this came from.
                FindingTitle.of(finding),
                basis(finding, read),
                severity(read),
                onset(finding),
                // The medians, in the measure's raw units — milliseconds for a duration, dollars for a
                // cost. Never a percentage: `magnitudePair` in the triage list renders a before/after pair
                // only for grader degradation, so these are read through `basis` where they carry a unit.
                read == null ? null : boxed(read.curP50()),
                read == null ? null : boxed(read.refP50()),
                delta(read));
    }

    /**
     * The case's lane: the measure that moved, read off the cause key's first segment.
     * {@code cause_key} is {@code <measure>:<bucket>:<direction>:<reference>} and a measure name never
     * contains a colon, so this is exact — and it is taken from the key rather than from the evidence
     * blob (which carries the same string) because {@code metric} is half the case's identity and the
     * key is the half that cannot be rewritten. A lane that moved when a jsonb column went bad would
     * open a second case for a regression that already had one.
     */
    private static String measureOf(FindingRow finding) {
        String causeKey = finding.nativeCauseKey();
        int colon = causeKey.indexOf(':');
        return colon > 0 ? causeKey.substring(0, colon) : finding.causeKind();
    }

    /**
     * Why this crossed <b>its own</b> bar, in this detector's terms and nobody else's. The ranked list
     * carries several detectors at once and they share no threshold: a CUSUM case next to this one fired
     * on sustained change in a bounded mean, and this one fired on an effect size in log units. Saying
     * "W₁ 0.34 on logs over 1,180 turns, against the window pinned at the last deploy" is what lets a
     * reader tell those apart; normalizing either onto the other's scale would make the list lie.
     *
     * <p>It also names the gate, because the gate is half of why this is a case at all. The statistic is
     * what noticed; the triage is what made it worth waking someone for.
     */
    private static String basis(FindingRow finding, @Nullable Read read) {
        String gate = gateSentence(finding);
        if (read == null) {
            return gate + " Its measured shift could not be read back from the finding — see the finding "
                    + "itself on the Classifiers page.";
        }
        return String.format(
                Locale.ROOT,
                "%s Distribution shift against %s — W₁ %.2f on logs over %d %s. Effect size, not "
                        + "significance: the bar is this population's own recent past, at any absolute level.",
                gate,
                referenceWords(read.reference()),
                Math.abs(read.w1Log()),
                read.nCur(),
                sampleWord(read.measure()));
    }

    /**
     * <b>Who confirmed this, and on what authority</b> (launch requirement B7). Two answers, and the
     * difference between them is the difference between two levels of trust: a <b>human</b> pressed
     * <em>Real deviation</em> — the strongest, and the only one that needed no machine at all — or a
     * <b>triage run</b> audited the claim and found it sound.
     *
     * <p>Spelled out on the case rather than left to the reader to infer, because a reader deciding
     * whether to page someone is entitled to know which of the two they are looking at.
     */
    private static String gateSentence(FindingRow finding) {
        if (FindingRow.Status.BLOCKED.equals(finding.status())) {
            return "A human ruled this a real deviation.";
        }
        return "A triage run audited this claim and found it sound.";
    }

    /**
     * The reference in words.
     *
     * <p>The wire value is {@code pinned} or {@code previous}, and {@code previous} stays {@code previous}
     * although it now names the rolling control rather than one closed window: it is the last segment of
     * every {@code cause_key} ever written, so renaming it would split one bucket's history into two
     * causes and reopen everything already resolved. Wire words are persisted strings; only the sentence
     * a human reads moves.
     */
    private static String referenceWords(String reference) {
        return Reference.PINNED.wire().equals(reference) ? "the window pinned at the last deploy" : "its recent normal";
    }

    private static String sampleWord(String measure) {
        return Measure.TOOL_DURATION.equals(measure) ? "calls" : "turns";
    }

    /** Ordering only, and never rendered as a number — the honest magnitude is the ratio in the title. */
    private static double severity(@Nullable Read read) {
        if (read == null) return UNKNOWN_SEVERITY;
        return Math.min(1, Math.abs(read.w1Log()) / SEVERITY_SATURATION);
    }

    /** An {@code OptionalDouble} as the nullable {@code Double} the detection record carries. */
    private static @Nullable Double boxed(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : null;
    }

    /** How far the median moved, in the measure's own units, or null when either side is unknown. */
    private static @Nullable Double delta(@Nullable Read read) {
        if (read == null || read.refP50().isEmpty() || read.curP50().isEmpty()) return null;
        return read.curP50().getAsDouble() - read.refP50().getAsDouble();
    }

    /**
     * When the CURRENT spell began: the first window that showed this shift, which is what
     * {@code behavior_finding.first_seen_at} records. The row is per cause, so that timestamp is stamped
     * once per spell and moves again only across a recovery — a gap longer than {@link #quietWindow},
     * which is the sweep's way of observing that the bucket went back to its reference
     * ({@code FindingRepository.recordShift}).
     *
     * <p><b>Stability WITHIN a spell is the load-bearing property, not precision.</b> The obvious
     * alternative, the evidence blob's {@code window.closed_at}, is the close time of the LATEST window
     * and is rewritten every time the shift re-fires — {@link CaseLedger} keys reopen-vs-stay-closed on
     * whether the onset moved, so an onset that advanced every pass would reopen every case a human had
     * just resolved, on the next tick. Freezing it for the row's whole lifetime was the overcorrection:
     * a bucket that recovered and shifted again inside {@code CaseLedger}'s reopen window then presented
     * an onset that had not moved, and its case stayed shut on a shift that was genuinely new.
     *
     * <p>What is given up is exactness under a backfill, where the sweep notices a window long after the
     * traffic in it happened; the finding's own evidence carries the true window bounds for a reader who
     * needs them.
     */
    private static @Nullable Instant onset(FindingRow finding) {
        return parse(finding.onsetAt());
    }

    /** A null onset says "this spell is unbracketed" rather than substituting {@code now} — see above. */
    private static @Nullable Instant parse(@Nullable String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
