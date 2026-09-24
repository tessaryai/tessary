// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits an agent's answer into the sentences that actually assert something checkable — the
 * decomposition step in front of the entailment head.
 *
 * <p><b>Why this exists.</b> Groundedness scored the whole answer as one claim against one premise,
 * which asks the head a question it cannot answer well in either direction. A multi-sentence answer
 * with one hallucinated clause reads as broadly supported; a pleasantry reads as unsupported,
 * because "You're welcome — anything else I can help with?" does not follow from a refund policy and
 * never will. Measured on 54 real turns with no evidence, the undecomposed detector fired on all 54
 * and 48 of those were exactly this: greetings, questions back to the user, and hand-backs. An
 * answer that yields NO verifiable claim is not a groundedness finding at all, and the honest
 * response is to abstain rather than to score it.
 *
 * <p><b>What this is not.</b> Real atomic decomposition — splitting compound assertions, resolving
 * "it" and "that order" against the conversation — needs a model. This is the deterministic tier: a
 * sentence split and a filter, no inference, no per-call cost, which is the only shape that belongs
 * in front of an L1 detector. It buys the abstain decision and sentence-grain scoring; it does not
 * buy atomicity. Its known weakness is the short flat assertion ("Damaged items are
 * non-refundable.") — no digit, no proper noun, few long words — which it drops.
 */
public final class VerifiableClaims {

    private VerifiableClaims() {}

    /** Sentence terminator followed by whitespace, or any newline run. */
    private static final Pattern SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\R+");

    /** Openers and closers that carry no checkable content even though they parse as declaratives. */
    private static final Pattern PHATIC = Pattern.compile(
            // Bare apologies only. An earlier revision matched `sorry\b.*`, which swallowed the whole
            // sentence after the word — so "Sorry, that item is non-refundable and your order shipped
            // on 3 March" was dropped as a pleasantry. An apology is a common PREFIX to a factual
            // denial, which is exactly the assertion worth checking.
            "^(k|ok|okay|sure|thanks|thank you|you'?re welcome|no problem|got it|understood"
                    + "|happy to help|glad to help|of course|certainly|absolutely|my pleasure"
                    + "|apologies|i apologise|i apologize|sorry|so sorry|i'?m sorry|very sorry)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * First-person narration and questions back to the user — the agent describing its own state,
     * capability, or actions, or asking for input, rather than asserting about the world.
     *
     * <p><b>The completed-action perfect ("I've issued a full refund") is here on purpose</b>, and a
     * revision of this class briefly removed it to catch agents reporting work they never did. That
     * was the wrong home for the idea. Nothing a retrieval returns can entail "I've escalated this to
     * our returns team" — the claim is about the agent's own behaviour, not about the source material —
     * so admitting it does not detect fabricated actions, it just fires groundedness on every honest
     * one. It is the same argument that put tool results out of scope: unverifiable against THIS
     * premise is a reason to abstain, not a reason to score. Fabricated actions need a check that can
     * see whether the action happened.
     *
     * <p>A leading first-person clause does NOT swallow a following assertion — see
     * {@link #isVerifiable}. "I'm sorry, but your order was already shipped" is a claim about the
     * order, whatever it opens with.
     */
    private static final Pattern META = Pattern.compile(
            // The NEGATED and CONTRACTED forms are listed explicitly. "i can " does not match "I can't",
            // so an honest refusal — "I'm sorry, but I can't help with that request", or the canonical
            // grounded abstention "I don't have information about that in the provided documents" —
            // read as an assertion, and no retrieved passage can entail one. That is the exact false
            // positive this class exists to remove.
            "^(i'?m |i am |i'?ll |i will |i'?d |i'?ve |i have "
                    + "|i can |i can'?t |i cannot |i could not |i couldn'?t "
                    + "|i won'?t |i will not |i do not |i don'?t |i did not |i didn'?t "
                    + "|let me |could you|can you|please "
                    + "|would you|which |what |when |where |how |why |is there|are there)",
            Pattern.CASE_INSENSITIVE);

    /** A clause break that can separate an opening hedge from the assertion it introduces. */
    private static final Pattern CLAUSE_BREAK = Pattern.compile("[,;:—]\\s+");

    private static final Pattern DIGIT = Pattern.compile("\\d");
    /** An identifier-shaped token: RMA-00000, KB-77, an all-caps acronym. */
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z]{2,}[-_ ]?\\d+|\\b[A-Z]{3,}\\b");

    private static final Pattern WORD = Pattern.compile("[A-Za-z']+");

    /** Clause-break recursion levels allowed — see {@link #hasStandaloneClauseAfterOpener}. */
    private static final int MAX_CLAUSE_DEPTH = 1;

    private static final int MIN_WORDS = 4;
    private static final int MIN_CONTENT_WORDS = 4;
    private static final int CONTENT_WORD_CHARS = 3;

    /** The verifiable sentences of {@code answer}, in order; empty when nothing is checkable. */
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
        // A first-person opener drops the sentence only when it IS the sentence. "I'm sorry, but your
        // order was already shipped" opens with a hedge and then asserts something about the order —
        // the same swallow the PHATIC set had with `sorry\b.*`, one pattern over. Only a clause break
        // counts, and only when what follows stands on its own: "I'm looking into that, please hold"
        // still drops, and "I'm not finding any orders on your account" has no break at all.
        if (META.matcher(core).find() && !anchored && !hasStandaloneClauseAfterOpener(core, depth)) {
            return false;
        }

        List<String> words = words(core);
        if (words.size() < MIN_WORDS) return false;
        // A capital after the first word is a proper noun in prose — a policy name, a product, a
        // payment method. The first word is skipped because every sentence starts capitalised.
        boolean properNoun = words.subList(1, words.size()).stream().anyMatch(w -> Character.isUpperCase(w.charAt(0)));
        long contentWords =
                words.stream().filter(w -> w.length() > CONTENT_WORD_CHARS).count();
        return anchored || properNoun || contentWords >= MIN_CONTENT_WORDS;
    }

    /**
     * Whether what follows the first clause break is itself a claim. Evaluated with the SAME rules, so a
     * hedge introducing a second hedge ("I'm looking into that, please hold") still drops.
     *
     * <p>Bounded to ONE level. The recursion terminates on its own — each level takes a strict suffix —
     * but its depth was the number of clause breaks in the sentence, and the sentence is uncapped model
     * output: 5,000 repetitions of "I'm sorry, " took 2.2 seconds and 50,000 raised StackOverflowError
     * after 45. {@code ClassifierWorker} catches {@code Exception}, not {@code Error}, so that killed the
     * sweep rather than one detection. One level is all the rule needs — a hedge chain still drops,
     * because the clause after the first break is judged by these same rules and a hedge fails them.
     */
    private static boolean hasStandaloneClauseAfterOpener(String core, int depth) {
        if (depth >= MAX_CLAUSE_DEPTH) return false;
        var m = CLAUSE_BREAK.matcher(core);
        if (!m.find()) return false;
        String rest = core.substring(m.end()).strip();
        // Drop a leading conjunction so "but your order was already shipped" is judged on its content.
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
