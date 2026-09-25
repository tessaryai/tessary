// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Linear connector. GraphQL answers 200 for a failed mutation, so the bugs are a 200 with
 * {@code errors} or {@code success:false} recorded as delivered, and a request that loses the team, the
 * key, or the description a reader of the issue needs.
 */
class LinearChannelTest {

    private static final String URL = "https://203.0.113.20/graphql";
    private static final String CONFIG = "{\"api_key\":\"lin_api_k\",\"team_id\":\"team-1\",\"url\":\"" + URL + "\"}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final LinearChannel channel = new LinearChannel(mapper);

    @AfterEach
    void resetSeam() {
        ChannelHttp.setClientForTest(null);
        Thread.interrupted();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "{\"team_id\":\"team-1\"}",
                "{\"api_key\":null,\"team_id\":\"team-1\"}",
                "{\"api_key\":\" \",\"team_id\":\"team-1\"}",
                "{\"api_key\":\"k\"}",
                "{\"api_key\":\"k\",\"team_id\":\"\"}"
            })
    void aConfigWithoutKeyAndTeamIsRefusedBeforeAnySend(String config) {
        ScriptedHttpClient client = ScriptedHttpClient.answering(200, "{}");
        ChannelHttp.setClientForTest(client);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));

        assertEquals(AlertError.INVALID_CHANNEL_CONFIG, e.error());
        assertNull(client.lastRequest, "nothing is sent on a config that cannot create an issue");
    }

    /** The issue carries the summary as its title and the firing's facts as its description. */
    @ParameterizedTest(name = "{1}")
    @MethodSource("issues")
    void aCreatedIssueIsDeliveredWithTitleDescriptionTeamAndKey(AlertEventRow event, String title, String description)
            throws Exception {
        ScriptedHttpClient client =
                ScriptedHttpClient.answering(200, "{\"data\":{\"issueCreate\":{\"success\":true}}}");
        ChannelHttp.setClientForTest(client);

        DeliveryResult result = channel.deliver(event, FiredEvents.config(mapper, CONFIG));

        assertEquals(DeliveryResult.success(200), result);
        assertEquals(URI.create(URL), requireNonNull(client.lastRequest).uri());
        assertEquals(
                "lin_api_k",
                client.lastRequest.headers().firstValue("Authorization").orElseThrow());
        JsonNode variables = mapper.readTree(client.lastBody).path("variables");
        assertEquals(title, variables.path("title").asText());
        assertEquals(description, variables.path("description").asText());
        assertEquals("team-1", variables.path("teamId").asText());
    }

    static Stream<Arguments> issues() {
        String window = "- **window**: 2026-01-15T09:00:00Z … ";
        return Stream.of(
                Arguments.of(
                        FiredEvents.digest(42),
                        "Daily digest: 42 events in 2026-01-15T09:00:00Z … 2026-01-15T10:00:00Z",
                        "Fired alert from tessary.\n\n- **kind**: digest\n- **project**: p1\n- **observed**: 42\n"
                                + window + "2026-01-15T10:00:00Z\n- **fired_at**: 2026-01-15T10:00:01Z\n"),
                Arguments.of(
                        FiredEvents.caseOpened(),
                        "C-3 · Latency up 2x",
                        "Fired alert from tessary.\n\n- **kind**: case_opened\n- **project**: p1\n" + window
                                + "2026-01-15T09:00:00Z\n- **fired_at**: 2026-01-15T10:00:01Z\n"));
    }

    /** Only a 2xx whose body says the mutation succeeded is a delivery. */
    @ParameterizedTest(name = "HTTP {0} {1}")
    @MethodSource("refusals")
    void aResponseThatDidNotCreateAnIssueIsAFailure(int status, String body, DeliveryResult expected) {
        ChannelHttp.setClientForTest(ScriptedHttpClient.answering(status, body));

        assertEquals(expected, channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, CONFIG)));
    }

    static Stream<Arguments> refusals() {
        DeliveryResult notCreated = DeliveryResult.failure(200, "linear issueCreate did not succeed");
        return Stream.of(
                Arguments.of(200, "{\"errors\":[{\"message\":\"team not found\"}]}", notCreated),
                Arguments.of(200, "{\"data\":{\"issueCreate\":{\"success\":false}}}", notCreated),
                Arguments.of(200, "<html>gateway</html>", notCreated),
                Arguments.of(401, "", DeliveryResult.failure(401, "linear returned HTTP 401")));
    }

    /**
     * A transport fault or a URL the SSRF guard refuses is a categorical failure that never echoes the
     * exception; an interrupt is reported as one and the thread's flag is restored for the caller.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    void aTransportFaultIsACategoricalFailure(
            String url, @Nullable Exception fault, DeliveryResult expected, boolean interrupted) {
        ChannelHttp.setClientForTest(
                fault == null ? ScriptedHttpClient.answering(200, "{}") : ScriptedHttpClient.failingWith(fault));
        String config = "{\"api_key\":\"k\",\"team_id\":\"t\",\"url\":\"" + url + "\"}";

        assertEquals(expected, channel.deliver(FiredEvents.digest(1), FiredEvents.config(mapper, config)));
        assertEquals(interrupted, Thread.currentThread().isInterrupted());
    }

    static Stream<Arguments> faults() {
        DeliveryResult network = DeliveryResult.failure(null, "linear delivery failed (network/guard)");
        return Stream.of(
                Arguments.of(URL, new IOException("connection reset by 10.0.0.7"), network, false),
                Arguments.of("http://127.0.0.1/graphql", null, network, false),
                Arguments.of(URL, new InterruptedException(), DeliveryResult.failure(null, "interrupted"), true));
    }
}
