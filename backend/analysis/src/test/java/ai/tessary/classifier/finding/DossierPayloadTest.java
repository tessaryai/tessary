// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The payload handed to an RCA agent: the detector's numbers without the undeclared trace-id samples. The
 * bugs are an id sample leaking through, and an unreadable blob losing the numbers that are the dossier's
 * whole point.
 */
class DossierPayloadTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"ratio\":2.4,\"failing_traces\":[\"t-1\"],\"changepoint_trace_id\":\"t-2\"}|{\"ratio\":2.4}",
                "{not json|{not json",
                "[1,2]|[1,2]",
            })
    void theAgentGetsTheNumbersWithoutTheIdSample(String payload, String forAgent) {
        assertEquals(forAgent, DossierPayload.forAgent(new ObjectMapper(), payload));
    }
}
