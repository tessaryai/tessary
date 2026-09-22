// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.classifier.frustration.JevFrustrationQuestion;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.decisions.DecisionAnswer;
import ai.tessary.llm.decisions.DecisionClient;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llm.decisions.DecisionTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A deterministic decision model for integration tests that sweep the Frustration classifier through
 * the worker: every project resolves a TypeSafe key for the frustration lane, and a turn whose current
 * message contains {@link #FRUSTRATED_PHRASE} is flagged well above the default threshold while every
 * other turn scores far below it.
 *
 * <p>Frustration only sends a turn with a user, assistant, user, assistant prefix, so a fixture needs
 * {@link ClassifierConversations#seedPreamble} ahead of the turn it expects to fire.
 */
@TestConfiguration
public class StubDecisionClientConfig {

    public static final String FRUSTRATED_PHRASE = "you're not listening";

    private static final String RESPONDED = "typesafe/jev-stub";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Bean
    @Primary
    DecisionProviderResolver stubDecisionProviderResolver() {
        DecisionProviderResolver resolver = mock(DecisionProviderResolver.class);
        when(resolver.resolve(any(), any()))
                .thenReturn(Optional.of(new DecisionTarget(
                        ModelProvider.TYPESAFE,
                        "jev-latest",
                        URI.create("https://api.typesafe.ai/v1/systemone"),
                        "k")));
        return resolver;
    }

    @Bean
    @Primary
    DecisionClient stubDecisionClient() {
        return (projectId, lane, target, request) -> {
            String current = request.state().path("current_user_message").asText("");
            double withAssistant = current.toLowerCase(Locale.ROOT).contains(FRUSTRATED_PHRASE) ? 0.95 : 0.02;
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", target.modelId());
            body.set("state", request.state());
            ObjectNode response = MAPPER.createObjectNode();
            response.put("model", RESPONDED);
            response.putObject("answers")
                    .putObject(JevFrustrationQuestion.NAME)
                    .putObject("probabilities")
                    .put(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT, withAssistant);
            return new DecisionAnswer(
                    ModelProvider.TYPESAFE,
                    target.modelId(),
                    RESPONDED,
                    Map.of(
                            JevFrustrationQuestion.NAME,
                            new DecisionAnswer.Answer(
                                    "choice",
                                    withAssistant > 0.5
                                            ? JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT
                                            : JevFrustrationQuestion.NEUTRAL_OR_POSITIVE,
                                    null,
                                    null,
                                    Map.of(JevFrustrationQuestion.UNHAPPY_WITH_ASSISTANT, withAssistant))),
                    800,
                    0,
                    new BigDecimal("0.000032"),
                    "book-1",
                    5,
                    body,
                    response);
        };
    }
}
