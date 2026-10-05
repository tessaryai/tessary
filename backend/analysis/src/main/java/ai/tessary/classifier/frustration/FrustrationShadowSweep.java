// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.Backlog;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.ShadowRow;
import ai.tessary.classifier.frustration.FrustrationShadowRepository.Summary;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-scores Frustration turns with the deployment's shadow decision model and keeps both answers
 * ({@link FrustrationShadowProperties}). Runs on a schedule; each run probes the shadow's
 * {@code /health} and, while it answers, drains the backlog in batches: the assessment's own request
 * body is sent as is, with only {@code model} replaced, so the shadow sees exactly what the reference
 * saw, and its probability of {@code unhappy_with_assistant} is flagged against the classifier's
 * current threshold, the rule the reference used.
 *
 * <p>Failure handling follows the server's nature, a GPU instance that stops itself when idle: a
 * transport failure or a 5xx ends the run quietly (the server is going down, the next run tries again),
 * a 4xx is recorded as a refusal so the turn is not re-sent forever, and a run yields after its time
 * budget. Nothing here pauses or touches the classifier; the shadow has no effect on what is flagged.
 */
@Component
public class FrustrationShadowSweep {

    private static final Logger log = LoggerFactory.getLogger(FrustrationShadowSweep.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final FrustrationShadowRepository shadows;
    private final ClassifierRepository classifiers;
    private final FrustrationShadowProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Clock clock;

    @Autowired
    public FrustrationShadowSweep(
            FrustrationShadowRepository shadows,
            ClassifierRepository classifiers,
            FrustrationShadowProperties props,
            ObjectMapper mapper) {
        this(
                shadows,
                classifiers,
                props,
                mapper,
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                Clock.systemUTC());
    }

    FrustrationShadowSweep(
            FrustrationShadowRepository shadows,
            ClassifierRepository classifiers,
            FrustrationShadowProperties props,
            ObjectMapper mapper,
            HttpClient http,
            Clock clock) {
        this.shadows = shadows;
        this.classifiers = classifiers;
        this.props = props;
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
    }

    /** What one run did. */
    public record Run(int sent, int scored, int refused, boolean serverUp, boolean budgetSpent) {}

    @Scheduled(fixedDelayString = "${tessary.frustration.shadow.sweep-ms:300000}", initialDelayString = "60000")
    public void scheduled() {
        if (!props.enabled()) return;
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("frustration shadow sweep failed error={}", e.getClass().getSimpleName());
        }
    }

    /** One run: nothing while the shadow is down, else the backlog until it is empty or the budget is spent. */
    public Run sweep() {
        if (!props.enabled()) return new Run(0, 0, 0, false, false);
        if (!healthy()) {
            log.debug("frustration shadow at {} is not answering; backlog waits", props.baseUrl());
            return new Run(0, 0, 0, false, false);
        }
        long deadline = clock.millis() + props.getBudgetMs();
        Map<String, Double> thresholds = new HashMap<>();
        int sent = 0;
        int scored = 0;
        int refused = 0;
        boolean budgetSpent = false;
        boolean serverUp = true;
        outer:
        while (true) {
            List<Backlog> batch = shadows.backlog(props.getBatch());
            if (batch.isEmpty()) break;
            for (Backlog b : batch) {
                if (clock.millis() >= deadline) {
                    budgetSpent = true;
                    break outer;
                }
                Answer answer = ask(b);
                if (answer == null) { // transport failure or 5xx: the server is going away
                    serverUp = false;
                    break outer;
                }
                sent++;
                double threshold = thresholds.computeIfAbsent(b.classifierId(), id -> threshold(b.projectId(), id));
                Double probability = answer.score();
                Boolean flagged = probability == null ? null : probability > threshold;
                BigDecimal score = probability == null ? null : BigDecimal.valueOf(probability);
                shadows.insert(new ShadowRow(
                        Ids.ulid(),
                        b.assessmentId(),
                        b.projectId(),
                        b.classifierId(),
                        b.traceId(),
                        b.scorerVersion(),
                        props.getModel(),
                        score,
                        flagged,
                        b.referenceFrustrated(),
                        answer.responseJson(),
                        answer.latencyMs()));
                if (probability == null) refused++;
                else scored++;
            }
        }
        Summary s = shadows.summary();
        StructuredLog.info(log, Markers.OPS, "frustration.shadow.sweep")
                .field("sent", sent)
                .field("scored", scored)
                .field("refused", refused)
                .field("server_up", serverUp)
                .field("budget_spent", budgetSpent)
                .field("total_scored", s.scored())
                .field("total_agree", s.agree())
                .field("total_reference_only", s.referenceOnly())
                .field("total_shadow_only", s.shadowOnly())
                .field("total_refused", s.refused())
                .log();
        return new Run(sent, scored, refused, serverUp, budgetSpent);
    }

    boolean healthy() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The shadow's answer to one turn; null when the server failed to answer at all. */
    record Answer(@Nullable Double score, String responseJson, int latencyMs) {}

    @Nullable
    Answer ask(Backlog b) {
        ObjectNode body;
        try {
            JsonNode stored = mapper.readTree(b.requestJson());
            if (!stored.isObject()) return refusal("stored request is not an object", 0);
            body = (ObjectNode) stored;
        } catch (IOException e) {
            return refusal("stored request is not JSON", 0);
        }
        body.put("model", props.getModel());
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/v1/systemone"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!props.getApiKey().isBlank()) req.header("Authorization", "Bearer " + props.getApiKey());
        long t0 = clock.millis();
        HttpResponse<String> response;
        try {
            response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        int latency = (int) Math.min(Integer.MAX_VALUE, clock.millis() - t0);
        int status = response.statusCode();
        if (status >= 500) return null;
        if (status / 100 != 2) {
            return refusal("HTTP " + status + ": " + cut(response.body()), latency);
        }
        Double score = score(response.body());
        if (score == null)
            return refusal(
                    "no probability for " + JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT + ": " + cut(response.body()),
                    latency);
        return new Answer(score, response.body(), latency);
    }

    /** {@code answers.<question>.probabilities.<main option>} of a 2xx body, else null. */
    @Nullable
    Double score(String body) {
        try {
            JsonNode p = mapper.readTree(body)
                    .path("answers")
                    .path(JevFrustrationQuestion.NAME)
                    .path("probabilities")
                    .path(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT);
            return p.isNumber() ? p.doubleValue() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private double threshold(String projectId, String classifierId) {
        return classifiers
                .findById(projectId, classifierId)
                .map(row -> JevFrustrationQuestion.threshold(row.configJson()))
                .orElse(JevFrustrationQuestion.DEFAULT_THRESHOLD);
    }

    private Answer refusal(String why, int latency) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", why);
        return new Answer(null, node.toString(), latency);
    }

    private static String cut(@Nullable String body) {
        if (body == null) return "";
        String s = body.strip().replaceAll("\\s+", " ");
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
