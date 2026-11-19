// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A cause the fix step may act on: high confidence, of a kind code can fix, and tied to a file in the repository. Its
 * {@code key} is the cause's type and file, not its title: each RCA words a cause afresh, and a key that changed with
 * the words would make every rerun look like a new cause.
 */
public record FixableCause(String key, String path, Cause cause) {

    static final Set<String> TYPES = Set.of("code", "prompt", "tool", "config", "data");

    /** A line or range after the path: {@code :42}, {@code :42-50}, {@code #L42} or {@code #L42-L50}. */
    private static final Pattern LINE_SUFFIX = Pattern.compile("(:\\d+(-\\d+)?|#L\\d+(-L\\d+)?)$");

    /** In report order; a second cause with the same key is left out, as the change would be the same. */
    public static List<FixableCause> of(List<Cause> causes) {
        List<FixableCause> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Cause c : causes) {
            String rawType = c.type();
            String type = rawType == null ? "" : rawType.toLowerCase(Locale.ROOT);
            Attribution at = c.attribution();
            String raw = at == null ? null : at.path();
            String path = raw == null ? "" : normalize(raw);
            if (!Cause.HIGH.equals(c.confidence()) || !TYPES.contains(type) || path.isEmpty()) continue;
            String key = type + ":" + path;
            if (seen.add(key)) out.add(new FixableCause(key, path, c));
        }
        return List.copyOf(out);
    }

    public static List<String> keys(List<FixableCause> causes) {
        return causes.stream().map(FixableCause::key).toList();
    }

    /** {@code ./src/a.py:12} and {@code src/a.py} name one file. */
    static String normalize(String path) {
        String p = LINE_SUFFIX.matcher(path.strip()).replaceFirst("");
        while (p.startsWith("./") || p.startsWith("/")) p = p.substring(p.startsWith("./") ? 2 : 1);
        return p;
    }
}
