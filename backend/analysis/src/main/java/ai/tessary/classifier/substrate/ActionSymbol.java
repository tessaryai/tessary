// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reduces one substrate action to {@code <kind>:<normalized-name>}. The tool-duration and tool-error buckets both key
 * on it, so their findings name the same tool.
 */
public final class ActionSymbol {

    public static final String UNNAMED = "unnamed";

    /** Names of these kinds are unbounded (a document id per call), so they bucket to a container. */
    private static final Set<String> BUCKETED_KINDS = Set.of("retrieval", "embedding", "reranker");

    private static final Pattern UUID =
            Pattern.compile("\\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\\b");
    private static final Pattern HEX_BLOB = Pattern.compile("\\b[0-9a-f]{16,}\\b");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{2,}");
    private static final Pattern TRAILING_ID = Pattern.compile("[_.:/-]?[0-9]+$");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final Pattern CORPUS_SPLIT = Pattern.compile("[/#?]");

    private ActionSymbol() {}

    /** Retrieval names bucket to their corpus so per-document names cannot explode the vocabulary. */
    public static String of(@Nullable String kind, @Nullable String name) {
        String k = normalizeName(kind);
        String raw = BUCKETED_KINDS.contains(k) ? corpusBucket(name) : name;
        String n = normalizeName(raw);
        if (n.isEmpty()) n = UNNAMED;
        return k + ":" + n;
    }

    /**
     * Strips uuids, hex blobs, digit runs and a trailing numeric id, then collapses non-alphanumeric runs to one
     * underscore. Returns {@code ""} for null or blank so callers can substitute their own fallback.
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

    /** {@code policy_docs/2024/s4.pdf} → {@code policy_docs}. */
    public static String corpusBucket(@Nullable String name) {
        if (name == null || name.isBlank()) return "";
        String[] parts = CORPUS_SPLIT.split(name.trim(), 2);
        return parts[0];
    }
}
