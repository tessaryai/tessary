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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Frustration, judged by TypeSafe's Jev decision model on the org's own OpenRouter or TypeSafe key.
 *
 * <p>Per page: a paused classifier sends nothing. Otherwise each turn root whose conversation is known
 * and whose turn is eligible ({@link FrustrationTurnBuilder}) is sent as one request carrying one
 * choice question ({@link JevFrustrationQuestion}), a few at a time. A turn fires when the probability
 * of {@code unhappy_with_assistant} exceeds the classifier's threshold; {@code unhappy_other_cause}
 * never fires. A refused key, or no key at all, pauses the classifier and abandons the page.
 *
 * <p>What a persisted page writes, in one transaction: an assessment row for every turn sent, flagged
 * or not, with the exact request and response bodies, and a detection row for every flagged turn
 * whose {@code subject_session_id} is the conversation key. The detection row is the conversation's
 * flag: while it stands uncleared the sweep sends no more of that conversation's turns. Ineligible turns, and turns
 * whose call failed, leave no row anywhere.
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
        return Math.max(0, props.getPageRetries());
    }

    /** One turn that was eligible and sent, and what came of it. */
    public record Sent(SubstrateObservation turn, EligibleTurn eligible, TurnFacts facts, Outcome outcome) {}

    /**
     * A call's outcome: an answer, or the failure that ended it.
     *
     * @param answer set on success
     * @param failure set on failure: {@code unavailable}, {@code rejected} or {@code failed}
     */
    public record Outcome(
            @Nullable DecisionAnswer answer, @Nullable String failure) {
        static final String UNAVAILABLE = "unavailable";
        static final String REJECTED = "rejected";
        static final String FAILED = "failed";
    }

    /**
     * A scored page.
     *
     * @param pauseReason set when the page ended paused or aborted
     * @param eligible turns that passed eligibility, whether or not they were sent
     * @param turns the eligible turns, each with its call's outcome
     */
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
            Duration wait = Duration.ofSeconds(Math.max(0, props.getCredentialRetrySeconds()));
            if (pause.get().pausedAt().plus(wait).isAfter(now)) {
                return new Page(Status.PAUSED, pause.get().reason(), 0, List.of(), threshold, scorerVersion);
            }
        }
        Optional<DecisionTarget> resolved = providers.resolve(projectId, ModelLane.FRUSTRATION);
        if (resolved.isEmpty()) {
            classifiers.pause(projectId, signal.id(), ClassifierPause.NO_PROVIDER, now);
            // A re-check that still finds no key passes the page, as the pause it extends would have.
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

        List<@Nullable Outcome> outcomes = send(projectId, target, eligible, eligibleFacts, threshold);
        List<Sent> sent = new ArrayList<>(eligible.size());
        for (int i = 0; i < eligible.size(); i++) {
            Outcome outcome = outcomes.get(i);
            if (outcome == null) continue;
            sent.add(new Sent(eligibleTurns.get(i), eligible.get(i), eligibleFacts.get(i), outcome));
        }
        if (sent.stream().anyMatch(s -> Outcome.REJECTED.equals(s.outcome().failure()))) {
            classifiers.pause(projectId, signal.id(), ClassifierPause.PROVIDER_REJECTED, now);
            return new Page(
                    Status.ABORTED, ClassifierPause.PROVIDER_REJECTED, eligible.size(), sent, threshold, scorerVersion);
        }
        return new Page(Status.SCORED, null, eligible.size(), sent, threshold, scorerVersion);
    }

    /**
     * Send the eligible turns, at most {@code concurrency} calls at once, returning outcomes in page order.
     * One conversation's turns go one at a time, earliest first, and stop at its first flag or refused
     * key: a turn after that is never sent and its outcome is null. Conversations run side by side.
     */
    private List<@Nullable Outcome> send(
            String projectId,
            DecisionTarget target,
            List<EligibleTurn> eligible,
            List<TurnFacts> facts,
            double threshold) {
        if (eligible.isEmpty()) return List.of();
        Map<String, List<Integer>> byConversation = new LinkedHashMap<>();
        for (int i = 0; i < eligible.size(); i++) {
            byConversation
                    .computeIfAbsent(Objects.requireNonNull(facts.get(i).conversationId()), k -> new ArrayList<>())
                    .add(i);
        }
        List<@Nullable Outcome> outcomes = new ArrayList<>(Collections.nCopies(eligible.size(), null));
        Semaphore permits = new Semaphore(Math.max(1, props.getConcurrency()));
        List<Future<?>> futures = new ArrayList<>(byConversation.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<Integer> indexes : byConversation.values()) {
                indexes.sort(Comparator.comparing((Integer i) -> facts.get(i).startedAt()));
                futures.add(executor.submit(() -> {
                    for (int i : indexes) {
                        Outcome outcome;
                        try {
                            permits.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        try {
                            outcome = call(projectId, target, eligible.get(i));
                        } catch (RuntimeException e) {
                            outcome = new Outcome(null, Outcome.FAILED);
                        } finally {
                            permits.release();
                        }
                        synchronized (outcomes) {
                            outcomes.set(i, outcome);
                        }
                        if (Outcome.REJECTED.equals(outcome.failure())) return;
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
     * Surface a task's failure. The executor's close has already waited for every task, so each future is
     * done. A task catches its own call's runtime failures, so only an {@link Error} or a failure outside the
     * call (scoring an answer) lands here, and it fails the page rather than dropping that conversation.
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
            if (e.error() == DecisionError.PROVIDER_UNAVAILABLE) return new Outcome(null, Outcome.UNAVAILABLE);
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
            // A page can hold two turns of one conversation; the conversation's flag is its first.
            Set<String> flaggedConversations = new HashSet<>();
            for (Sent s : page.turns()) {
                DecisionAnswer answer = s.outcome().answer();
                if (answer == null) continue;
                double score = score(answer);
                boolean frustrated = score > page.threshold();
                assessments.insert(assessment(signal, page, s, answer, frustrated));
                if (!frustrated || !flaggedConversations.add(s.facts().conversationId())) continue;
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

    /** The probability of {@code unhappy_with_assistant}. */
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

    /** The request as sent, minus the model id, which the row carries in its own column. */
    private static Object stateAndQuestions(DecisionAnswer answer) {
        if (!answer.requestBody().isObject()) return answer.requestBody();
        ObjectNode copy = ((ObjectNode) answer.requestBody()).deepCopy();
        copy.remove("model");
        return copy;
    }

    /** What the finding page shows about one flagged turn. */
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
                            signal.classifierKey(),
                            ClassifierPause.PROVIDER_REJECTED.equals(page.pauseReason())
                                    ? "the provider rejected the org's key"
                                    : "no provider key is configured for the frustration lane")
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
