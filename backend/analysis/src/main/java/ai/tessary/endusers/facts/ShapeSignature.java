// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.endusers.judge.JudgeConstants;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The shape key of spec 3.3: the leading letters before the first of {@code _ - : .}, and the length of the ID in code
 * points, as the reference {@code prefix_of} and {@code len} read them. Stored as {@link #wire()}, so it stays when the
 * ID text is deleted.
 */
public record ShapeSignature(String prefix, int length) {

    private static final Pattern PREFIX = Pattern.compile("([A-Za-z]+)[_\\-:.]");

    public static ShapeSignature of(String id) {
        Matcher m = PREFIX.matcher(id);
        return new ShapeSignature(m.lookingAt() ? m.group(1) : "", id.codePointCount(0, id.length()));
    }

    /** {@code prefix:length}; a prefix holds letters only, so the last colon splits it. */
    public String wire() {
        return prefix + ":" + length;
    }

    public static ShapeSignature parse(String wire) {
        int at = wire.lastIndexOf(':');
        return new ShapeSignature(wire.substring(0, at), Integer.parseInt(wire.substring(at + 1)));
    }

    /** The text before the last {@code :}, {@code /} or {@code |}; null when there is none or it is empty. */
    public static @Nullable String clusterOf(String id) {
        int at = Math.max(id.lastIndexOf(':'), Math.max(id.lastIndexOf('/'), id.lastIndexOf('|')));
        return at > 0 ? id.substring(0, at) : null;
    }

    /**
     * The clusters that count as accounts: those that 5 or more IDs share, and only when 3 or more such clusters exist.
     */
    public static <K> Set<K> accountClusters(Map<K, Integer> idsPerCluster) {
        Set<K> big = idsPerCluster.entrySet().stream()
                .filter(e -> e.getValue() >= JudgeConstants.CLUSTER_MIN_IDS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        return big.size() >= JudgeConstants.CLUSTER_MIN_CLUSTERS ? big : Set.of();
    }

    /**
     * The salted key of the cluster text, made like a user key; the text never starts with white space, and white
     * space at its end is dropped.
     */
    public static byte[] clusterHash(byte[] salt, String cluster) {
        return EndUserKeys.userKey(salt, cluster);
    }
}
