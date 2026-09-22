// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

/**
 * Cuts over-cap text to its first and last halves joined by {@value #ELLIPSIS}. Head and tail beat a
 * head-only cut because a user's complaint as often closes a message as opens it. Each cut moves
 * outward to the nearest whitespace, so no word is split; a single word longer than its half is cut
 * where it stands. The ellipsis is added on top of the cap, as the research formatter adds it.
 */
public final class HeadTailClip {

    static final String ELLIPSIS = " ... ";

    private HeadTailClip() {}

    public static String clip(String text, int cap) {
        if (cap < 2) {
            throw new IllegalArgumentException("a head-and-tail cap needs at least one char per side: " + cap);
        }
        if (text.length() <= cap) {
            return text;
        }
        int headEnd = snapHeadEnd(text, cap / 2);
        int tailStart = snapTailStart(text, text.length() - (cap - cap / 2));
        return text.substring(0, headEnd).strip()
                + ELLIPSIS
                + text.substring(tailStart).strip();
    }

    /** The head's end, moved back to the last whitespace when it falls inside a word. */
    private static int snapHeadEnd(String text, int end) {
        if (Character.isWhitespace(text.charAt(end)) || Character.isWhitespace(text.charAt(end - 1))) {
            return end;
        }
        for (int i = end - 1; i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    /** The tail's start, moved forward past the next whitespace when it falls inside a word. */
    private static int snapTailStart(String text, int start) {
        if (Character.isWhitespace(text.charAt(start)) || Character.isWhitespace(text.charAt(start - 1))) {
            return start;
        }
        for (int i = start; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        }
        return Character.isLowSurrogate(text.charAt(start)) ? start + 1 : start;
    }
}
