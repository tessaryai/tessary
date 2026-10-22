// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.ModelsDevProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.testsupport.Props;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What a triage or RCA run spends, through the real {@link LauncherAgentSessionClient} against a {@link FakeLauncher}:
 * the session starts with the lane's model cost, and each reply is booked once on the run's own subject, a corrected
 * run and a failed run included.
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionRunnerBookingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROJECT = "proj";
    private static final ModelsDevRates RATES =
            new ModelsDevRates(MAPPER, Props.of(ModelsDevProperties.class, "url", ""));
    private static final AgenticCredentialResolver.Credential CRED = new AgenticCredentialResolver.Credential(
            ModelProvider.GROK, "xai-key", null, null, null, null, null, false, null, "c");
    private static final String USAGE_JSON = "{\"input_tokens\":1200,\"output_tokens\":80,"
            + "\"cache_read_input_tokens\":1000,\"cache_creation_input_tokens\":200,\"cost_usd\":0.0123}";
    private static final String RULING = "{\"verdict\":\"real\"}";

    @Mock
    ProjectModelSettings modelSettings;

    @Mock
    AgenticCredentialResolver credentials;

    @Mock
    LlmUsageAccountant usage;

    private FakeLauncher fake;

    @BeforeEach
    void startTheLauncher() throws IOException {
        fake = FakeLauncher.start();
    }

    @AfterEach
    void stopTheLauncher() throws IOException {
        fake.close();
    }

    static Stream<Arguments> lanes() {
        return Stream.of(
                Arguments.of(ModelLane.TRIAGE, new LlmUsageAccountant.Subject("finding", "f-1"), true),
                Arguments.of(ModelLane.TRIAGE, new LlmUsageAccountant.Subject("finding", "f-1"), false),
                Arguments.of(ModelLane.RCA, new LlmUsageAccountant.Subject("rca_report", "r-1"), true),
                Arguments.of(ModelLane.RCA, new LlmUsageAccountant.Subject("rca_report", "r-1"), false));
    }

    /**
     * A reply's spend lost or booked twice, or booked on another lane's ledger: a run that needed the one correction
     * books both replies once each on its lane and subject, whether the corrected reply answered or the run failed,
     * and its start carries the lane's models.dev cost.
     */
    @ParameterizedTest
    @MethodSource("lanes")
    void everyReplyOfARunIsBookedOnceOnItsSubjectWhetherTheRunAnswersOrFails(
            ModelLane lane, LlmUsageAccountant.Subject subject, boolean answers) throws Exception {
        when(modelSettings.resolveAgenticModel(PROJECT, lane))
                .thenReturn(Optional.of(
                        new ProjectModelSettings.ResolvedAgenticModel(ModelProvider.GROK, "grok-4.6", "xai/grok-4.6")));
        when(credentials.resolve(PROJECT, ModelProvider.GROK)).thenReturn(CRED);
        fake.answer("POST", "/sessions", 201, "{\"session_id\":\"s-1\"}");
        fake.answer("POST", "/sessions/s-1/messages", 202, null);
        fake.answer("DELETE", "/sessions/s-1", 204, null);
        fake.events(
                "/sessions/s-1/events?after=0",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(1, "text", "{\"delta\":\"It is real.\"}"),
                FakeLauncher.frame(2, "message_end", "{\"usage\":" + USAGE_JSON + ",\"reason\":\"done\"}"));
        fake.events(
                "/sessions/s-1/events?after=2",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(
                        3,
                        "text",
                        MAPPER.createObjectNode()
                                .put("delta", answers ? RULING : "Still real.")
                                .toString()),
                FakeLauncher.frame(4, "message_end", "{\"usage\":" + USAGE_JSON + ",\"reason\":\"done\"}"));
        fake.events(
                "/sessions/s-1/events?after=4",
                FakeLauncher.Ending.END,
                FakeLauncher.frame(5, "closed", "{\"reason\":\"stopped\"}"));
        AgentSessionRunner runner =
                new AgentSessionRunner(client(), MAPPER, Clock.systemUTC(), new FakeAgentSessionClient.FakeDeadlines());
        AgentSessionRunner.Task task = new AgentSessionRunner.Task(
                PROJECT,
                lane,
                subject,
                lane == ModelLane.RCA ? null : "You rule.",
                Map.of(),
                List.of(),
                null,
                "Rule.",
                "{\"type\":\"object\",\"required\":[\"verdict\"]}",
                Duration.ofMinutes(15),
                38,
                StepWords.Context.NONE);
        AgentSessionRunner.StepSink sink = new AgentSessionRunner.StepSink() {
            @Override
            public void session(String sessionId) {}

            @Override
            public void started(String callId, StepWords.Words w, long atMs) {}

            @Override
            public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {}

            @Override
            public void note(String words, long atMs) {}
        };

        if (answers) {
            assertEquals(RULING, runner.run(task, sink).json());
        } else {
            assertEquals(
                    AgentRunError.BAD_OUTPUT,
                    assertThrows(TessaryException.class, () -> runner.run(task, sink))
                            .error());
        }

        verify(usage, times(2))
                .recordSandboxRun(
                        PROJECT,
                        lane.wire(),
                        "grok-4.6",
                        false,
                        1200L,
                        80L,
                        1000L,
                        200L,
                        new BigDecimal("0.0123"),
                        subject);
        verifyNoMoreInteractions(usage);
        JsonNode start = MAPPER.readTree(fake.seen().getFirst().body());
        assertEquals(lane.wire(), start.path("lane").asText());
        assertEquals(
                MAPPER.readTree(RATES.cost("xai/grok-4.6")
                        .orElseThrow()
                        .toOpencodeCost(MAPPER)
                        .toString()),
                start.path("model_cost"));
    }

    private LauncherAgentSessionClient client() {
        AgentRunProperties props =
                Props.of(AgentRunProperties.class, "launcher-url", fake.url(), "launcher-api-key", "launcher-k");
        ObserverProperties observer =
                Props.of(ObserverProperties.class, "agentic.launcher-url", "", "agentic.launcher-api-key", "");
        return new LauncherAgentSessionClient(
                props, observer, new AgentModels(modelSettings, observer, RATES), credentials, usage, MAPPER);
    }
}
