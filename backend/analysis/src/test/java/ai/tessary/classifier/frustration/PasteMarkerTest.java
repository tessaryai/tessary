// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the port against the research formatter's {@code mark_pastes}: every expected string here is
 * that function's output on the same input, so the Java and Python renderers agree character for
 * character on {@code [PASTE: ...]}.
 */
class PasteMarkerTest {

    static Stream<Arguments> texts() {
        String sevenSteps = "see:\n" + steps(7) + "done";
        String traceback =
                "it failed again:\nTraceback (most recent call last):\n  File \"app.py\", line 3, in <module>\n"
                        + "    main()\n  File \"app.py\", line 2, in main\n    parse(x)\n  File \"p.py\", line 9, in parse\n"
                        + "    return a[1]\nIndexError: list index out of range\nwhy??";
        return Stream.of(
                Arguments.of("log:\n~~~\nERROR a\nERROR b\n~~~\nfix it", "log:\n[PASTE: 4 lines, 23 chars]\nfix it"),
                Arguments.of("x ``` y", "x ``` y"),
                Arguments.of("see:\n" + steps(8) + "done", "see:\n[PASTE: 8 lines, 69 chars]\ndone"),
                // Seven code-like lines are not a paste; eight are.
                Arguments.of(sevenSteps, sevenSteps),
                Arguments.of(
                        "server says\nINFO start\nWARN slow\nERROR boom\nINFO retry\nERROR boom\nINFO retry\n"
                                + "ERROR boom\nINFO stop\nwhat now",
                        "server says\n[PASTE: 8 lines, 85 chars]\nwhat now"),
                Arguments.of(traceback, traceback));
    }

    @ParameterizedTest
    @MethodSource("texts")
    void markMatchesTheResearchFormatter(String in, String marked) {
        assertEquals(marked, PasteMarker.mark(in));
    }

    private static String steps(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append("  step ").append(i).append('\n');
        return b.toString();
    }
}
