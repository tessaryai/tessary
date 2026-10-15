// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

/**
 * Cuts over-cap text to its first and last halves joined by {@value #ELLIPSIS}, since a complaint as often closes a
 * message as opens it. Cuts snap outward to whitespace; one over-long word is cut where it stands. The ellipsis is
 * added on top of the cap, as the research formatter does.
 */
public final class HeadTailClip {

    static final String ELLIPSIS = " ... ";

    private HeadTailClip() {}

    public static String clip(String text, int cap) {
        if (text.length() <= cap) {
            return text;
        }
        int headEnd = snapHeadEnd(text, cap / 2);
        int tailStart = snapTailStart(text, text.length() - (cap - cap / 2));
        return text.substring(0, headEnd).strip()
                + ELLIPSIS
                + text.substring(tailStart).strip();
    }

    private static int snapHeadEnd(String text, int end) {
        if (Character.isWhitespace(text.charAt(end)) || Character.isWhitespace(text.charAt(end - 1))) {
            return end;
        }
        for (int i = end - 1; i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

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
