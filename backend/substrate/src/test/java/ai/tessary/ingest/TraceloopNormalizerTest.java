// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TraceloopNormalizerTest {

    /** Each Traceloop span kind lands on the gen_ai operation that normalizes to the same kind of step. */
    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "llm, chat",
                "' Agent ', invoke_agent",
                "tool, execute_tool",
                "workflow, invoke_workflow",
                "task, invoke_workflow",
                "embedding, null",
                "null, null"
            })
    void spanKindMapsToItsOperation(@Nullable String spanKind, @Nullable String operation) {
        assertEquals(operation, TraceloopNormalizer.operationName(spanKind));
    }
}
