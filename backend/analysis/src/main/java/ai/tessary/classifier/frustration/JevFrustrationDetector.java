// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierPause;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.PagedDetector;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.Assessment;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository.TurnFacts;
import ai.tessary.classifier.frustration.FrustrationTurnBuilder.EligibleTurn;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.config.FrustrationProperties;
import ai.tessary.llm.decisions.DecisionAnswer;
import ai.tessary.llm.decisions.DecisionClient;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llm.decisions.DecisionRequest;
import ai.tessary.llm.decisions.DecisionTarget;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.DecisionError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A turn fires when P({@code unhappy_with_assistant}) exceeds the threshold; {@code unhappy_other_cause} never fires.
 * An uncleared detection is the session's flag, and the sweep sends no more of that session's turns. A refused key, no
 * key, or no credit pauses the classifier and abandons the page. Ineligible and failed turns leave no row.
 */
@Component
public class JevFrustrationDetector implements PagedDetector<JevFrustrationDetector.Page> {

    private static final Logger log = LoggerFactory.getLogger(JevFrustrationDetector.class);

    static final String LANE = ModelLane.FRUSTRATION.wire();

    private final FrustrationTurnBuilder builder;
    private final DecisionClient client;
    private final DecisionProviderResolver providers;
    private final FrustrationAssessmentRepository assessments;
    private final ClassifierDetectionWriteRepository detections;
    private final ClassifierRepository classifiers;
    private final TransactionOperations tx;
    private final FrustrationProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    JevFrustrationDetector(
            ConversationThreadAssembler assembler,
            DecisionClient client,
            DecisionProviderResolver providers,
            FrustrationAssessmentRepository assessments,
            ClassifierDetectionWriteRepository detections,
            ClassifierRepository classifiers,
            TransactionOperations tx,
            FrustrationProperties props,
            ObjectMapper mapper) {
        this(
                new FrustrationTurnBuilder(assembler),
                client,
                providers,
                assessments,
                detections,
                classifiers,
                tx,
                props,
                mapper,
                Clock.systemUTC());
    }

    JevFrustrationDetector(
            FrustrationTurnBuilder builder,
            DecisionClient client,
            DecisionProviderResolver providers,
            FrustrationAssessmentRepository assessments,
            ClassifierDetectionWriteRepository detections,
            ClassifierRepository classifiers,
            TransactionOperations tx,
            FrustrationProperties props,
            ObjectMapper mapper,
            Clock clock) {
        this.builder = builder;
        this.client = client;
        this.providers = providers;
        this.assessments = assessments;
        this.detections = detections;
        this.classifiers = classifiers;
        this.tx = tx;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String kind() {
        return BuiltInDetector.Kind.FRUSTRATION;
    }

    @Override
    public int maxPageRetries() {
        return Math.max(0, props.pageRetries());
    }

    public record Sent(SubstrateObservation turn, EligibleTurn eligible, TurnFacts facts, Outcome outcome) {}

    public record Outcome(
            @Nullable DecisionAnswer answer, @Nullable String failure) {
        static final String UNAVAILABLE = "unavailable";
        static final String REJECTED = "rejected";
        static final String REFUSED = "refused";

        static final String NO_CREDIT = "no_credit";
        static final String FAILED = "failed";

        boolean stopsTheKey() {
            return REJECTED.equals(failure) || REFUSED.equals(failure) || NO_CREDIT.equals(failure);
        }
    }

    public record Page(
            Status status,
            @Nullable String pauseReason,
            int eligible,
            List<Sent> turns,
            double threshold,
            String scorerVersion)
            implements PagedDetector.ScoredPage {

        public Page {
            turns = List.copyOf(turns);
        }

        @Override
        public int sent() {
            return turns.size();
        }

        @Override
        public int unavailable() {
            return count(Outcome.UNAVAILABLE);
        }

        int failed() {
            return turns.size()
                    - (int) turns.stream()
                            .filter(s -> s.outcome().answer() != null)
                            .count();
        }

        private int count(String failure) {
            return (int) turns.stream()
                    .filter(s -> failure.equals(s.outcome().failure()))
                    .count();
        }
    }

    @Override
    public Page score(ClassifierRow signal, List<SubstrateObservation> turns) {
        String projectId = signal.projectId();
        double threshold = JevFrustrationQuestion.threshold(signal.configJson());
        String scorerVersion = JevFrustrationQuestion.scorerVersion(threshold);
        Instant now = clock.instant();

        Optional<ClassifierPause> pause = classifiers.findPause(projectId, signal.id());
        boolean recheck = pause.isPresent();
        if (pause.isPresent()) {
            Duration wait = Duration.ofSeconds(Math.max(0, props.credentialRetrySeconds()));
            if (pause.get().pausedAt().plus(wait).isAfter(now)) {
                return new Page(Status.PAUSED, pause.get().reason(), 0, List.of(), threshold, scorerVersion);
            }
        }
        Optional<DecisionTarget> resolved = providers.resolve(projectId, ModelLane.FRUSTRATION);
        if (resolved.isEmpty()) {
            classifiers.pause(projectId, signal.id(), ClassifierPause.NO_PROVIDER, now);
            // A re-check that still finds nothing to run on passes the page.
            Status status = recheck ? Status.PAUSED : Status.ABORTED;
            return new Page(status, ClassifierPause.NO_PROVIDER, 0, List.of(), threshold, scorerVersion);
        }
        DecisionTarget target = resolved.get();
        if (recheck) classifiers.unpause(projectId, signal.id());

        Map<String, TurnFacts> facts = assessments.turnFacts(
                projectId, turns.stream().map(SubstrateObservation::traceId).toList());
        List<SubstrateObservation> eligibleTurns = new ArrayList<>();
        List<EligibleTurn> eligible = new ArrayList<>();
        List<TurnFacts> eligibleFacts = new ArrayList<>();
        for (SubstrateObservation turn : turns) {
            TurnFacts f = facts.get(turn.traceId());
            if (f == null || f.conversationId() == null) continue;
            Optional<EligibleTurn> e = builder.buildTurn(turn);
            if (e.isEmpty()) continue;
            eligibleTurns.add(turn);
            eligible.add(e.get());
            eligibleFacts.add(f);
        }

        List<@Nullable Outcome> outcomes = send(projectId, target, eligibleTurns, eligible, eligibleFacts, threshold);
        List<Sent> sent = new ArrayList<>(eligible.size());
        for (int i = 0; i < eligible.size(); i++) {
            Outcome outcome = outcomes.get(i);
            if (outcome == null) continue;
            sent.add(new Sent(eligibleTurns.get(i), eligible.get(i), eligibleFacts.get(i), outcome));
        }
        boolean rejected =
                sent.stream().anyMatch(s -> Outcome.REJECTED.equals(s.outcome().failure()));
        boolean refused =
                sent.stream().anyMatch(s -> Outcome.REFUSED.equals(s.outcome().failure()));
        if (rejected || refused || sent.stream().anyMatch(s -> s.outcome().stopsTheKey())) {
            // A refused request on the org's own key is request_refused.
            String reason;
            if (rejected) reason = ClassifierPause.PROVIDER_REJECTED;
            else if (refused) reason = ClassifierPause.REQUEST_REFUSED;
            else reason = ClassifierPause.NO_CREDIT;
            classifiers.pause(projectId, signal.id(), reason, now);
            return new Page(Status.ABORTED, reason, eligible.size(), sent, threshold, scorerVersion);
        }
        return new Page(Status.SCORED, null, eligible.size(), sent, threshold, scorerVersion);
    }

    /**
     * One session's turns go one at a time and stop at its first flag or refused key. Once interrupted, no further turn
     * is sent, even by a task that wins a just-released permit.
     */
    private List<@Nullable Outcome> send(
            String projectId,
            DecisionTarget target,
            List<SubstrateObservation> turns,
            List<EligibleTurn> eligible,
            List<TurnFacts> facts,
            double threshold) {
        if (eligible.isEmpty()) return List.of();
        Map<Session, List<Integer>> bySession = new LinkedHashMap<>();
        for (int i = 0; i < eligible.size(); i++) {
            bySession
                    .computeIfAbsent(session(turns.get(i), facts.get(i)), k -> new ArrayList<>())
                    .add(i);
        }
        List<@Nullable Outcome> outcomes = new ArrayList<>(Collections.nCopies(eligible.size(), null));
        Semaphore permits = new Semaphore(Math.max(1, props.concurrency()));
        // Set before an interrupted call releases its permit, so the release carries it to the next holder.
        AtomicBoolean stopped = new AtomicBoolean();
        List<Future<?>> futures = new ArrayList<>(bySession.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<Integer> indexes : bySession.values()) {
                indexes.sort(Comparator.comparing((Integer i) -> facts.get(i).startedAt()));
                futures.add(executor.submit(() -> {
                    for (int i : indexes) {
                        Outcome outcome;
                        try {
                            permits.acquire();
                        } catch (InterruptedException e) {
                            stopped.set(true);
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (Thread.currentThread().isInterrupted()) stopped.set(true);
                        if (stopped.get()) {
                            permits.release();
                            return;
                        }
                        try {
                            outcome = call(projectId, target, eligible.get(i));
                        } catch (RuntimeException e) {
                            outcome = new Outcome(null, Outcome.FAILED);
                        } finally {
                            if (Thread.currentThread().isInterrupted()) stopped.set(true);
                            permits.release();
                        }
                        synchronized (outcomes) {
                            outcomes.set(i, outcome);
                        }
                        if (outcome.stopsTheKey()) return;
                        DecisionAnswer answer = outcome.answer();
                        if (answer != null && score(answer) > threshold) return;
                    }
                }));
            }
        }
        joinAll(futures);
        return outcomes;
    }

    /**
     * Only an {@link Error} or a failure outside the call lands here, and it fails the page rather than dropping that
     * session.
     */
    private static void joinAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            if (f.state() == Future.State.FAILED) {
                throw new IllegalStateException("frustration send task failed", f.exceptionNow());
            }
        }
    }

    private Outcome call(String projectId, DecisionTarget target, EligibleTurn turn) {
        DecisionRequest request =
                new DecisionRequest(mapper.valueToTree(turn.state()), JevFrustrationQuestion.questions());
        try {
            return new Outcome(client.decide(projectId, LANE, target, request), null);
        } catch (TessaryException e) {
            if (e.error() == DecisionError.PROVIDER_REJECTED) return new Outcome(null, Outcome.REJECTED);
            if (e.error() == DecisionError.PROVIDER_NO_CREDIT) return new Outcome(null, Outcome.NO_CREDIT);
            if (e.error() == DecisionError.PROVIDER_UNAVAILABLE) return new Outcome(null, Outcome.UNAVAILABLE);
            if (e.error() == DecisionError.REQUEST_REFUSED) {
                // The one failure caused by the provider's answer, so it is logged once with the provider's words.
                String detail = Objects.requireNonNullElse(e.getMessage(), DecisionError.REQUEST_REFUSED.code());
                StructuredLog.warn(log, Markers.OPS, "signal.frustration.refused")
                        .message("%s refused a frustration call: %s", target.provider(), detail)
                        .field("project", projectId)
                        .field("provider", target.provider().name())
                        .field("model", target.modelId())
                        .field("error", detail)
                        .log();
                return new Outcome(null, Outcome.REFUSED);
            }
            return new Outcome(null, Outcome.FAILED);
        }
    }

    @Override
    public List<FiredTurn> complete(ClassifierRow signal, Page page, PageAction action, long durationMs) {
        List<FiredTurn> fired = action == PageAction.PERSIST ? persist(signal, page) : List.of();
        logPage(signal, page, action, fired.size(), durationMs);
        return fired;
    }

    private List<FiredTurn> persist(ClassifierRow signal, Page page) {
        List<FiredTurn> fired = tx.execute(status -> {
            List<FiredTurn> out = new ArrayList<>();
            Set<Session> flaggedSessions = new HashSet<>();
            for (Sent s : page.turns()) {
                DecisionAnswer answer = s.outcome().answer();
                if (answer == null) continue;
                double score = score(answer);
                boolean frustrated = score > page.threshold();
                assessments.insert(assessment(signal, page, s, answer, frustrated));
                if (!frustrated || !flaggedSessions.add(session(s.turn(), s.facts()))) continue;
                String detectionId = Ids.ulid();
                boolean inserted = detections.insert(
                        detectionId,
                        signal.detector(),
                        signal.projectId(),
                        signal.id(),
                        signal.classifierKey(),
                        s.turn().projectVersionId(),
                        s.facts().conversationId(),
                        s.turn().traceId(),
                        s.turn().observationId(),
                        Detection.Severity.WARN,
                        Detection.Confidence.HIGH,
                        evidence(page, s, answer, score));
                if (inserted) out.add(new FiredTurn(s.turn(), detectionId, Detection.Severity.WARN));
            }
            return out;
        });
        return fired == null ? List.of() : fired;
    }

    /** One turn can reach several call sites, each judged and stopped on its own. */
    private record Session(String conversationId, String callSiteId) {}

    private static Session session(SubstrateObservation turn, TurnFacts facts) {
        return new Session(
                Objects.requireNonNull(facts.conversationId()),
                Objects.requireNonNull(turn.callSiteId(), "a turn candidate has a call site"));
    }

    static double score(DecisionAnswer answer) {
        DecisionAnswer.Answer a = answer.answers().get(JevFrustrationQuestion.NAME);
        return a == null ? 0.0 : a.probability(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT);
    }

    private Assessment assessment(ClassifierRow signal, Page page, Sent s, DecisionAnswer answer, boolean frustrated) {
        return new Assessment(
                Ids.ulid(),
                signal.projectId(),
                signal.id(),
                s.turn().traceId(),
                s.turn().observationId(),
                Objects.requireNonNull(s.facts().conversationId()),
                s.turn().callSiteId(),
                s.facts().startedAt(),
                frustrated,
                page.scorerVersion(),
                answer.provider().name(),
                answer.respondedModel(),
                json(stateAndQuestions(answer)),
                json(answer.responseBody()),
                answer.inputTokens(),
                answer.costUsd(),
                (int) Math.min(Integer.MAX_VALUE, answer.latencyMs()));
    }

    private static Object stateAndQuestions(DecisionAnswer answer) {
        if (!answer.requestBody().isObject()) return answer.requestBody();
        ObjectNode copy = ((ObjectNode) answer.requestBody()).deepCopy();
        copy.remove("model");
        return copy;
    }

    private String evidence(Page page, Sent s, DecisionAnswer answer, double score) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("model", answer.respondedModel());
        e.put("provider", answer.provider().name());
        e.put("score", Math.round(score * 1000) / 1000.0);
        e.put("threshold", page.threshold());
        e.put("scorer_version", page.scorerVersion());
        e.put("k_turn", s.eligible().userTurn());
        e.put("call_site_id", s.turn().callSiteId());
        return json(e);
    }

    private String json(Object value) {
        return mapper.valueToTree(value).toString();
    }

    private static String pauseCause(@Nullable String reason) {
        if (ClassifierPause.PROVIDER_REJECTED.equals(reason)) return "the provider rejected the org's key";
        if (ClassifierPause.REQUEST_REFUSED.equals(reason)) {
            return "the provider refused the request itself on the org's key; see signal.frustration.refused";
        }
        if (ClassifierPause.NO_CREDIT.equals(reason)) return "the org has no credit left on the provider";
        return "no provider key is configured for the frustration lane";
    }

    private void logPage(ClassifierRow signal, Page page, PageAction action, int fired, long durationMs) {
        int inputTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (Sent s : page.turns()) {
            DecisionAnswer a = s.outcome().answer();
            if (a == null) continue;
            Integer tokens = a.inputTokens();
            if (tokens != null) inputTokens += tokens;
            BigDecimal callCost = a.costUsd();
            if (callCost != null) cost = cost.add(callCost);
        }
        if (page.status() == Status.ABORTED) {
            StructuredLog.warn(log, Markers.OPS, "signal.frustration.paused")
                    .message(
                            "paused %s: %s; the page was not recorded",
                            signal.classifierKey(), pauseCause(page.pauseReason()))
                    .field("signal", signal.classifierKey())
                    .field("classifierId", signal.id())
                    .field("project", signal.projectId())
                    .field("reason", page.pauseReason())
                    .log();
        }
        StructuredLog.info(log, Markers.OPS, "signal.frustration.page")
                .message(
                        "%s page for %s: %d eligible, %d sent, %d fired, %d failed",
                        action.name().toLowerCase(Locale.ROOT),
                        signal.classifierKey(),
                        page.eligible(),
                        page.sent(),
                        fired,
                        page.failed())
                .field("signal", signal.classifierKey())
                .field("classifierId", signal.id())
                .field("project", signal.projectId())
                .field("action", action.name().toLowerCase(Locale.ROOT))
                .field("paused", page.pauseReason())
                .field("eligible", page.eligible())
                .field("sent", page.sent())
                .field("fired", fired)
                .field("failed", page.failed())
                .field("held", action == PageAction.HOLD)
                .field("inputTokens", inputTokens)
                .field("costUsd", cost.doubleValue())
                .field("durationMs", durationMs)
                .log();
    }
}
