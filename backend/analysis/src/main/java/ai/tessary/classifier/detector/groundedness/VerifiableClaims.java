// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scoring a whole answer as one claim fired on pleasantries: on 54 evidence-free turns the undecomposed detector fired
 * on all 54, 48 of them greetings and hand-backs. An answer with no verifiable claim abstains. Deterministic tier only
 * (split and filter, no model); it drops short flat assertions with no digit or proper noun.
 */
public final class VerifiableClaims {

    private VerifiableClaims() {}

    private static final Pattern SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\R+");

    private static final Pattern PHATIC = Pattern.compile(
            // Bare apologies only: an apology is a common prefix to a factual denial worth checking.
            "^(k|ok|okay|sure|thanks|thank you|you'?re welcome|no problem|got it|understood"
                    + "|happy to help|glad to help|of course|certainly|absolutely|my pleasure"
                    + "|apologies|i apologise|i apologize|sorry|so sorry|i'?m sorry|very sorry)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Completed-action perfects ("I've issued a refund") are here on purpose: no retrieval can entail the agent's own
     * actions, so scoring them fires on every honest one. A leading first-person clause does not swallow a following
     * assertion; see {@link #isVerifiable}.
     */
    private static final Pattern META = Pattern.compile(
            // Negated and contracted forms are listed explicitly, or an honest refusal reads as an assertion no passage
            // can entail.
            "^(i'?m |i am |i'?ll |i will |i'?d |i'?ve |i have "
                    + "|i can |i can'?t |i cannot |i could not |i couldn'?t "
                    + "|i won'?t |i will not |i do not |i don'?t |i did not |i didn'?t "
                    + "|let me |could you|can you|please "
                    + "|would you|which |what |when |where |how |why |is there|are there)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLAUSE_BREAK = Pattern.compile("[,;:—]\\s+");

    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z]{2,}[-_ ]?\\d+|\\b[A-Z]{3,}\\b");

    private static final Pattern WORD = Pattern.compile("[A-Za-z']+");

    private static final int MAX_CLAUSE_DEPTH = 1;

    private static final int MIN_WORDS = 4;
    private static final int MIN_CONTENT_WORDS = 4;
    private static final int CONTENT_WORD_CHARS = 3;

    public static List<String> of(@org.jspecify.annotations.Nullable String answer) {
        List<String> out = new ArrayList<>();
        if (answer == null || answer.isBlank()) return out;
        for (String raw : SPLIT.split(answer.strip())) {
            String sentence = raw.strip();
            if (!sentence.isEmpty() && isVerifiable(sentence, 0)) out.add(sentence);
        }
        return out;
    }

    private static boolean isVerifiable(String sentence, int depth) {
        if (sentence.endsWith("?")) return false; // a question asserts nothing
        String core = trimTerminator(sentence);
        if (core.isEmpty()) return false;
        if (PHATIC.matcher(core).matches()) return false;

        boolean anchored =
                DIGIT.matcher(core).find() || IDENTIFIER.matcher(core).find();
        // A first-person opener drops the sentence only when it is the whole sentence; a standalone clause after a
        // break is judged on its own.
        if (META.matcher(core).find() && !anchored && !hasStandaloneClauseAfterOpener(core, depth)) {
            return false;
        }

        List<String> words = words(core);
        if (words.size() < MIN_WORDS) return false;
        // The first word is skipped because every sentence starts capitalised.
        boolean properNoun = words.subList(1, words.size()).stream().anyMatch(w -> Character.isUpperCase(w.charAt(0)));
        long contentWords =
                words.stream().filter(w -> w.length() > CONTENT_WORD_CHARS).count();
        return anchored || properNoun || contentWords >= MIN_CONTENT_WORDS;
    }

    /**
     * Bounded to one level: uncapped model output with thousands of clause breaks took seconds and then overflowed the
     * stack, which {@code ClassifierWorker} (catching Exception, not Error) does not survive.
     */
    private static boolean hasStandaloneClauseAfterOpener(String core, int depth) {
        if (depth >= MAX_CLAUSE_DEPTH) return false;
        var m = CLAUSE_BREAK.matcher(core);
        if (!m.find()) return false;
        String rest = core.substring(m.end()).strip();
        rest = rest.replaceFirst("^(?i)(but|and|however|though|although)\\s+", "");
        return !rest.isEmpty() && isVerifiable(rest, depth + 1);
    }

    private static String trimTerminator(String s) {
        int end = s.length();
        while (end > 0 && ".!?".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end).strip();
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        var m = WORD.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }
}
