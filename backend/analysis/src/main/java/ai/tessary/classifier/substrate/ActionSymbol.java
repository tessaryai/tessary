// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The behaviour-drift alphabet: the pure reduction of one substrate action to a symbol
 * {@code <kind>:<normalized-name>[:err]}, per {@code tessary-paid/classifiers/behavior_drift/PROGRAM.md} §2.
 *
 * <p>Carrying the error flag <em>inside</em> the symbol is deliberate: "this tool now fails where it
 * used to succeed" is drift, and folding it into the alphabet means the n-gram machinery detects it
 * for free rather than needing a separate rule.
 */
public final class ActionSymbol {

    /** Synthetic start padding — what makes "conversations now start differently" detectable. */
    public static final String START = "^";

    /** Synthetic end padding — what makes "the confirmation step disappeared" detectable. */
    public static final String END = "$";

    /** The name every below-floor name collapses to (per kind). */
    public static final String RARE = "__rare__";

    /** The kind whose name is deliberately discarded — see {@link #of}. */
    public static final String LLM_KIND = "llm";

    /**
     * The one symbol every LLM call reduces to (§2.1). A dispatching call never reaches the sequence
     * as this symbol — {@link TrajectoryAssembler} replaces it with the fan-out it opened — so in
     * practice this marks the call that produced an answer rather than more tool calls.
     */
    public static final String LLM_ANSWER = LLM_KIND + ":answer";

    /** Opens a fan-out block: one LLM call that requested {@code k >= 2} actions at once. */
    public static final String FORK_PREFIX = "fork:";

    /** Closes a fan-out block. */
    public static final String JOIN = "join";

    /** The name used when an observation carries none. */
    public static final String UNNAMED = "unnamed";

    /** The kind used when an observation carries none (the substrate allows a null {@code kind}). */
    public static final String UNKNOWN_KIND = "unknown";

    /** Separator between the symbols of one n-gram key. Symbols never contain whitespace. */
    public static final String GRAM_SEPARATOR = " ";

    private static final String ERROR_SUFFIX = ":err";
    /**
     * The kinds whose name is unbounded in cardinality and must bucket to a container rather than be
     * kept verbatim — a document or chunk id per call would make every sequence unique, which is the
     * "too fine" failure §2 opens with. Only {@code retrieval} was bucketed here while the Python
     * reference bucketed all three, so {@code embedding:vecs/x.pdf} reduced to {@code embedding:vecs}
     * offline and {@code embedding:vecs_x_pdf} in production. Both kinds are dispatchable, so the
     * divergence also moved the symbol's slot in a sorted fan-out block.
     */
    private static final Set<String> BUCKETED_KINDS = Set.of("retrieval", "embedding", "reranker");

    private static final Pattern UUID =
            Pattern.compile("\\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\\b");
    private static final Pattern HEX_BLOB = Pattern.compile("\\b[0-9a-f]{16,}\\b");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{2,}");
    private static final Pattern TRAILING_ID = Pattern.compile("[_.:/-]?[0-9]+$");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final Pattern CORPUS_SPLIT = Pattern.compile("[/#?]");

    private ActionSymbol() {}

    /**
     * The symbol for one action. Retrieval names bucket to their corpus (the leading path segment) so
     * a per-document name space cannot explode the vocabulary.
     *
     * <p>An LLM span's name is discarded entirely. Producers name that span after the tool batch it
     * requested — {@code "llm → verify_member, get_policy, check_hospital"} — which is a description of
     * the NEXT actions, not an action itself. Keeping it put the emission order of a concurrent batch
     * inside the alphabet: one measured project recorded 37 real batches as 68 distinct symbols, so
     * reordering a batch minted a brand-new symbol and fired novelty, while {@link #normalizeName}'s
     * trailing-id strip simultaneously merged batches of four, five and six tools into one. What the
     * call did is carried structurally instead, by {@link TrajectoryAssembler}.
     */
    public static String of(@Nullable String kind, @Nullable String name, boolean isError) {
        String k = (kind == null || kind.isBlank()) ? UNKNOWN_KIND : normalizeName(kind);
        if (LLM_KIND.equals(k)) {
            return isError ? LLM_ANSWER + ERROR_SUFFIX : LLM_ANSWER;
        }
        String raw = BUCKETED_KINDS.contains(k) ? corpusBucket(name) : name;
        String n = normalizeName(raw);
        if (n.isEmpty()) n = UNNAMED;
        return isError ? k + ":" + n + ERROR_SUFFIX : k + ":" + n;
    }

    /**
     * Lowercase, strip uuids / long hex blobs / digit runs / a trailing numeric id, and collapse every
     * remaining non-alphanumeric run to a single underscore. Returns {@code ""} for a null/blank input
     * so callers can substitute their own fallback.
     */
    public static String normalizeName(@Nullable String name) {
        if (name == null || name.isBlank()) return "";
        String s = name.trim().toLowerCase(Locale.ROOT);
        s = UUID.matcher(s).replaceAll("");
        s = HEX_BLOB.matcher(s).replaceAll("");
        s = TRAILING_ID.matcher(s).replaceAll("");
        s = DIGIT_RUN.matcher(s).replaceAll("");
        s = NON_WORD.matcher(s).replaceAll("_");
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '_') from++;
        while (to > from && s.charAt(to - 1) == '_') to--;
        return s.substring(from, to);
    }

    /** The leading path segment of a retrieval name: {@code policy_docs/2024/s4.pdf} → {@code policy_docs}. */
    public static String corpusBucket(@Nullable String name) {
        if (name == null || name.isBlank()) return "";
        String[] parts = CORPUS_SPLIT.split(name.trim(), 2);
        return parts[0];
    }

    /** The {@code <kind>} half of a symbol (everything before the first colon). */
    public static String kindOf(String symbol) {
        int colon = symbol.indexOf(':');
        return colon < 0 ? symbol : symbol.substring(0, colon);
    }

    /** True for the synthetic padding symbols. */
    public static boolean isPadding(String symbol) {
        return START.equals(symbol) || END.equals(symbol);
    }

    /** The fan-out marker for a batch of {@code width} concurrently-requested actions. */
    public static String fork(int width) {
        return FORK_PREFIX + width;
    }

    /**
     * True for every symbol the assembler synthesises rather than reads off a span — padding and the
     * fan-out markers. None is subject to the rare-name floor: the floor exists to bound an unbounded
     * NAME space, and these come from a closed set. Collapsing {@code fork:6} to {@code __rare__}
     * because wide batches are uncommon would erase the width and merge fan-outs of different degree
     * into one symbol — the same information loss the floor is meant to prevent elsewhere.
     */
    public static boolean isStructural(String symbol) {
        return isPadding(symbol) || JOIN.equals(symbol) || symbol.startsWith(FORK_PREFIX);
    }

    /**
     * The {@code <kind>:__rare__} collapse for a below-floor name. The {@code :err} suffix survives —
     * the floor bounds the <em>name</em> space, and dropping the error flag with it would erase the
     * "this tool now fails" signal.
     */
    public static String collapseRare(String symbol) {
        String kind = kindOf(symbol);
        return symbol.endsWith(ERROR_SUFFIX) ? kind + ":" + RARE + ERROR_SUFFIX : kind + ":" + RARE;
    }

    /**
     * The symbols in {@code corpus} whose share of all observations falls below {@code floorFraction}
     * (default 0.1%) — the set the caller collapses with {@link #collapseRare}. {@linkplain
     * #isStructural Structural} symbols are exempt.
     */
    public static Set<String> rareSymbols(List<String> corpus, double floorFraction) {
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (String s : corpus) {
            if (isStructural(s)) continue;
            counts.merge(s, 1, Integer::sum);
            total++;
        }
        if (total == 0) return Set.of();
        double floor = floorFraction * total;
        Set<String> rare = new HashSet<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() < floor) rare.add(e.getKey());
        }
        return rare;
    }

    /** Apply a {@link #rareSymbols} verdict to one sequence, leaving padding and frequent names intact. */
    public static List<String> applyRareFloor(List<String> symbols, Set<String> rare) {
        if (rare.isEmpty()) return symbols;
        List<String> out = new ArrayList<>(symbols.size());
        for (String s : symbols) {
            out.add(!isStructural(s) && rare.contains(s) ? collapseRare(s) : s);
        }
        return out;
    }

    /** Wrap a sequence in the synthetic {@code ^ … $} padding. */
    public static List<String> pad(List<String> symbols) {
        List<String> out = new ArrayList<>(symbols.size() + 2);
        out.add(START);
        out.addAll(symbols);
        out.add(END);
        return List.copyOf(out);
    }

    /** The stable string key for an n-gram — the {@code behavior_ngram.gram_key} column value. */
    public static String gramKey(List<String> parts) {
        return String.join(GRAM_SEPARATOR, parts);
    }

    /** Every n-gram of order {@code order} in {@code symbols}, in position order. */
    public static List<String> grams(List<String> symbols, int order) {
        if (order < 1 || symbols.size() < order) return List.of();
        List<String> out = new ArrayList<>(symbols.size() - order + 1);
        for (int i = 0; i + order <= symbols.size(); i++) {
            out.add(gramKey(symbols.subList(i, i + order)));
        }
        return out;
    }
}
