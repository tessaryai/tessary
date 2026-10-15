// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * An Aho-Corasick automaton over every rule's keywords, case-insensitive on ASCII: a rule's regex runs only when a
 * keyword appears, as gitleaks prefilters. Case is folded per character so a large body is never copied; a non-ASCII
 * character resets the walk.
 */
final class KeywordIndex {

    private static final int ALPHABET = 128;

    private final List<int[]> next = new ArrayList<>();
    private final List<BitSet> outputs = new ArrayList<>();
    private final int[] fail;
    private final BitSet alwaysCandidates;
    private final int ruleCount;

    /** @param keywordsByRule a rule with no keywords is a candidate for every text */
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
     * Turns missing edges into failure-chain transitions so the walk never backtracks; outputs absorb the failure
     * state's, so a keyword that is a suffix of another is still reported.
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
