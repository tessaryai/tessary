// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the port against the research formatter's {@code mark_pastes}: every expected string here is
 * that function's output on the same input, so the Java and Python renderers agree character for
 * character on {@code [PASTE: ...]}.
 */
class PasteMarkerTest {

    @Test
    void mark_replacesATildeFence() {
        assertEquals(
                "log:\n[PASTE: 4 lines, 23 chars]\nfix it",
                PasteMarker.mark("log:\n~~~\nERROR a\nERROR b\n~~~\nfix it"));
    }

    @Test
    void mark_leavesAnUnclosedFenceAlone() {
        assertEquals("x ``` y", PasteMarker.mark("x ``` y"));
    }

    @Test
    void mark_replacesARunOfEightIndentedLines() {
        StringBuilder in = new StringBuilder("see:\n");
        for (int i = 0; i < 8; i++) in.append("  step ").append(i).append('\n');
        in.append("done");
        assertEquals("see:\n[PASTE: 8 lines, 69 chars]\ndone", PasteMarker.mark(in.toString()));
    }

    @Test
    void mark_leavesARunOfSevenLinesAlone() {
        StringBuilder in = new StringBuilder("see:\n");
        for (int i = 0; i < 7; i++) in.append("  step ").append(i).append('\n');
        in.append("done");
        assertEquals(in.toString(), PasteMarker.mark(in.toString()), "seven code-like lines are not a paste");
    }

    @Test
    void mark_countsLogLevelLinesAsCodeLike() {
        assertEquals(
                "server says\n[PASTE: 8 lines, 85 chars]\nwhat now",
                PasteMarker.mark("server says\nINFO start\nWARN slow\nERROR boom\nINFO retry\nERROR boom\nINFO retry\n"
                        + "ERROR boom\nINFO stop\nwhat now"));
    }

    @Test
    void mark_leavesASevenLineTracebackAlone() {
        String traceback =
                "it failed again:\nTraceback (most recent call last):\n  File \"app.py\", line 3, in <module>\n"
                        + "    main()\n  File \"app.py\", line 2, in main\n    parse(x)\n  File \"p.py\", line 9, in parse\n"
                        + "    return a[1]\nIndexError: list index out of range\nwhy??";
        assertEquals(traceback, PasteMarker.mark(traceback));
    }
}
