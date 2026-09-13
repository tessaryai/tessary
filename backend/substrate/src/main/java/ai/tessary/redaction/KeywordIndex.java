// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Which rules could possibly match a text, found in one pass over it: an Aho-Corasick automaton over
 * every rule's keywords, matched case-insensitively on ASCII.
 *
 * <p>This is what makes a corpus of two hundred credential rules affordable on the ingest path. Running
 * every regex over every body would be two hundred scans per field; instead a rule's regex runs only when
 * one of its keywords appears, which is the prefilter gitleaks itself uses, and finding the keywords is a
 * single left-to-right walk however many there are.
 *
 * <p>Case is folded per character during the walk rather than by lowercasing the text first, so a
 * multi-megabyte body costs no copy. Keywords are ASCII; a non-ASCII character can be part of no keyword
 * and resets the walk to the root.
 */
final class KeywordIndex {

    private static final int ALPHABET = 128;

    private final List<int[]> next = new ArrayList<>();
    private final List<BitSet> outputs = new ArrayList<>();
    private final int[] fail;
    private final BitSet alwaysCandidates;
    private final int ruleCount;

    /**
     * @param keywordsByRule each rule's keywords, by rule index; a rule with none is a candidate for every
     *     text, since there is nothing to prefilter it on
     */
    KeywordIndex(List<List<String>> keywordsByRule) {
        this.ruleCount = keywordsByRule.size();
        this.alwaysCandidates = new BitSet(ruleCount);
        addNode();
        for (int rule = 0; rule < ruleCount; rule++) {
            List<String> keywords = keywordsByRule.get(rule);
            if (keywords.isEmpty()) {
                alwaysCandidates.set(rule);
                continue;
            }
            for (String keyword : keywords) insert(keyword.toLowerCase(Locale.ROOT), rule);
        }
        this.fail = new int[next.size()];
        link();
    }

    /** The rules whose keywords occur in {@code text}, plus every rule that has no keywords. */
    BitSet candidates(String text) {
        BitSet found = (BitSet) alwaysCandidates.clone();
        int state = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= ALPHABET) {
                state = 0;
                continue;
            }
            state = next.get(state)[Character.toLowerCase(c)];
            BitSet out = outputs.get(state);
            if (!out.isEmpty()) {
                found.or(out);
                if (found.cardinality() == ruleCount) return found;
            }
        }
        return found;
    }

    private int addNode() {
        int[] edges = new int[ALPHABET];
        java.util.Arrays.fill(edges, -1);
        next.add(edges);
        outputs.add(new BitSet(ruleCount));
        return next.size() - 1;
    }

    private void insert(String keyword, int rule) {
        int state = 0;
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c >= ALPHABET) return; // a keyword no ASCII walk can reach; the rule still runs on its others
            int to = next.get(state)[c];
            if (to < 0) {
                to = addNode();
                next.get(state)[c] = to;
            }
            state = to;
        }
        outputs.get(state).set(rule);
    }

    /**
     * Fill in the failure links breadth-first and turn every missing edge into the transition the failure
     * chain would take, so the walk never backtracks. A state's outputs absorb its failure state's, which
     * is how a keyword that is a suffix of another is still reported.
     */
    private void link() {
        Deque<Integer> queue = new ArrayDeque<>();
        int[] root = next.get(0);
        for (int c = 0; c < ALPHABET; c++) {
            if (root[c] < 0) {
                root[c] = 0;
            } else {
                fail[root[c]] = 0;
                queue.add(root[c]);
            }
        }
        while (!queue.isEmpty()) {
            int state = queue.poll();
            int[] edges = next.get(state);
            for (int c = 0; c < ALPHABET; c++) {
                int to = edges[c];
                if (to < 0) {
                    edges[c] = next.get(fail[state])[c];
                } else {
                    fail[to] = next.get(fail[state])[c];
                    outputs.get(to).or(outputs.get(fail[to]));
                    queue.add(to);
                }
            }
        }
    }
}
