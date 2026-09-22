// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.decisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.tessary.llm.ModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The REAL {@link JevDecisionClient} against the real endpoints. Opt-in: skipped unless a key is set,
 * and each gateway runs only with its own key. Two calls per gateway, well under a cent.
 *
 * <pre>
 *   TYPESAFE_API_KEY=... OPENROUTER_API_KEY=... \
 *     mvn test -pl llm-runtime -Dtest=JevDecisionClientLiveIT
 * </pre>
 */
@EnabledIf("anyKey")
class JevDecisionClientLiveIT {

    private static final Logger log = LoggerFactory.getLogger(JevDecisionClientLiveIT.class);

    private static final String UNHAPPY = "unhappy_with_assistant";

    private final ObjectMapper mapper = new ObjectMapper();

    static boolean anyKey() {
        return present("TYPESAFE_API_KEY") || present("OPENROUTER_API_KEY");
    }

    private static boolean present(String name) {
        String v = System.getenv(name);
        return v != null && !v.isBlank();
    }

    @Test
    void typesafe_scoresAnAnnoyedTurnHighAndANeutralOneLow() {
        assumeTrue(present("TYPESAFE_API_KEY"));
        scoresBothTurns(ModelProvider.TYPESAFE, "jev-latest", System.getenv("TYPESAFE_API_KEY"));
    }

    @Test
    void openRouter_scoresAnAnnoyedTurnHighAndANeutralOneLow() {
        assumeTrue(present("OPENROUTER_API_KEY"));
        scoresBothTurns(ModelProvider.OPENROUTER, "typesafe/jev-latest", System.getenv("OPENROUTER_API_KEY"));
    }

    private void scoresBothTurns(ModelProvider provider, String model, String key) {
        JevDecisionClient client = new JevDecisionClient(
                HttpClient.newHttpClient(),
                mapper,
                OpenTelemetry.noop(),
                null,
                null,
                Thread::sleep,
                Duration.ofSeconds(20),
                3);
        DecisionTarget target = new DecisionTarget(provider, model, DecisionTarget.endpointFor(provider, null), key);

        DecisionAnswer annoyed = client.decide(
                "live", "frustration", target, request("I ALREADY told you the date is in the header. Read it."));
        DecisionAnswer neutral =
                client.decide("live", "frustration", target, request("Thanks. Can you also add the page count?"));

        for (DecisionAnswer a : new DecisionAnswer[] {annoyed, neutral}) {
            double sum = a.answers().get("user_stance").probabilities().values().stream()
                    .mapToDouble(Double::doubleValue)
                    .sum();
            assertEquals(1.0, sum, 0.02, "probabilities sum to about one");
            log.info(
                    "{} answered by {} in {}ms, {} input tokens",
                    provider,
                    a.respondedModel(),
                    a.latencyMs(),
                    a.inputTokens());
        }
        assertTrue(annoyed.answers().get("user_stance").probability(UNHAPPY) > 0.5, "annoyed turn");
        assertTrue(neutral.answers().get("user_stance").probability(UNHAPPY) < 0.2, "neutral turn");
    }

    private DecisionRequest request(String current) {
        ObjectNode state = mapper.createObjectNode();
        state.put("current_user_message", current);
        ArrayNode earlier = state.putArray("earlier_messages");
        earlier.addObject().put("role", "user").put("content", "Pull the invoice date out of this PDF.");
        earlier.addObject().put("role", "assistant").put("content", "The invoice date is not in the document.");
        earlier.addObject().put("role", "user").put("content", "It is in the header, top right.");
        earlier.addObject()
                .put("role", "assistant")
                .put("content", "I could not find a date. Could you paste it here?");
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put(UNHAPPY, "Visibly frustrated, disappointed, or hostile because of the assistant.");
        criteria.put("unhappy_other_cause", "Negative, but about something outside the chat.");
        criteria.put("neutral_or_positive", "Neutral, positive, only confused or urgent, or too ambiguous to say.");
        String instructions = "Judge only current_user_message; earlier_messages are the turns before it, oldest"
                + " first. Unhappy means frustration, disappointment or hostility caused by the assistant, visible"
                + " in the user's own words or in re-asking after a clear failure. Emotion inside pasted or"
                + " requested content does not count. If unsure, pick neutral_or_positive. Which describes the"
                + " current message?";
        return new DecisionRequest(
                state, Map.of("user_stance", DecisionRequest.Question.choice(instructions, criteria)));
    }
}
