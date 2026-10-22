// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The answer a session reply carries, read the way the one-shot lanes read it in {@code agent-stream.js}. */
class JsonAnswerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Arguments> replies() {
        String quoted = "{\"verdict\":\"real\",\"why\":\"a stray } and a { \\\"quote\\\"\"}";
        return Stream.of(
                Arguments.of("{\"verdict\":\"real\"}", "{\"verdict\":\"real\"}"),
                Arguments.of("```json\n{\"verdict\":\"real\"}\n```", "{\"verdict\":\"real\"}"),
                Arguments.of("Here it is: " + quoted + " done", quoted),
                Arguments.of("{not json} then {\"verdict\":\"real\"}", "{\"verdict\":\"real\"}"),
                Arguments.of("{\"verdict\":\"real\",\"why\":\"cut at the tok", null),
                Arguments.of("no object at all", null),
                Arguments.of("{1} {2} {3} {4} {\"verdict\":\"real\"}", null));
    }

    /**
     * A ruling lost to its wrapping: a fence, prose on both sides and braces inside strings, balanced or not, still yield the object; a
     * reply truncated mid-object, with no object, or with four candidates before it, yields nothing.
     */
    @ParameterizedTest
    @MethodSource("replies")
    void theAnswerIsTheFirstBalancedObjectThatParses(String reply, @Nullable String expected) throws Exception {
        Optional<String> want = expected == null
                ? Optional.empty()
                : Optional.of(MAPPER.readTree(expected).toString());
        assertEquals(want, JsonAnswer.extract(MAPPER, reply).map(Object::toString));
    }

    /** A partial ruling accepted: the top-level required keys a reply leaves out are named, in schema order. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"verdict":"real","why":"x"} | []
            {"verdict":"real"}           | [why]
            {"verdict":null}             | [why]
            {}                           | [verdict, why]
            """)
    void missingKeysNamesTheRequiredKeysAReplyLeavesOut(String reply, String missing) throws Exception {
        var schema = MAPPER.readTree("{\"type\":\"object\",\"required\":[\"verdict\",\"why\"]}");
        assertEquals(
                missing, JsonAnswer.missingKeys(MAPPER.readTree(reply), schema).toString());
        assertEquals(List.of(), JsonAnswer.missingKeys(MAPPER.readTree(reply), MAPPER.readTree("{}")));
    }
}
