// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces pasted code and logs in a message with {@code [PASTE: n lines, k chars]}, so the budget is
 * spent on what the user wrote rather than what they pasted, and emotion inside a paste is not read as
 * the user's. Two shapes are pastes: a fenced block ({@code ```} or {@code ~~~}), and a run of at least
 * {@value #MIN_RUN_LINES} consecutive code-like lines. A port of the research formatter's
 * {@code mark_pastes}; the two must agree character for character, which {@code PasteMarkerTest} pins.
 */
public final class PasteMarker {

    static final int MIN_RUN_LINES = 8;

    // UNICODE_CHARACTER_CLASS makes \s the full Unicode whitespace set, as Python's re does on a str.
    private static final Pattern FENCE = Pattern.compile("```.*?```|~~~.*?~~~", Pattern.DOTALL);
    private static final Pattern INDENTED = Pattern.compile("\\s{2,}\\S", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CODE_OR_LOG =
            Pattern.compile("[{};=<>]|^\\s*(at |File \"|Traceback|ERROR|WARN|INFO)", Pattern.UNICODE_CHARACTER_CLASS);

    private PasteMarker() {}

    public static String mark(String text) {
        Matcher fences = FENCE.matcher(text);
        StringBuilder unfenced = new StringBuilder();
        while (fences.find()) {
            fences.appendReplacement(unfenced, Matcher.quoteReplacement(marker(fences.group())));
        }
        fences.appendTail(unfenced);

        List<String> out = new ArrayList<>();
        List<String> run = new ArrayList<>();
        for (String line : unfenced.toString().split("\n", -1)) {
            if (isCodeLike(line) && !line.strip().isEmpty()) {
                run.add(line);
                continue;
            }
            flush(run, out);
            out.add(line);
        }
        flush(run, out);
        return String.join("\n", out);
    }

    private static boolean isCodeLike(String line) {
        return INDENTED.matcher(line).lookingAt() || CODE_OR_LOG.matcher(line).find();
    }

    private static void flush(List<String> run, List<String> out) {
        if (run.size() >= MIN_RUN_LINES) {
            out.add(marker(String.join("\n", run)));
        } else {
            out.addAll(run);
        }
        run.clear();
    }

    private static String marker(String block) {
        String body = block.strip();
        long lines = body.chars().filter(c -> c == '\n').count() + 1;
        return "[PASTE: " + lines + " lines, " + body.codePointCount(0, body.length()) + " chars]";
    }
}
