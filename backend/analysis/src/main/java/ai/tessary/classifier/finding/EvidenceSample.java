// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.ArrayList;
import java.util.List;

/**
 * A sample of evidence rows drawn by a rule a reader can audit: rank-even over the caller's order, with no randomness,
 * so the same rows give the same sample on every run.
 */
public final class EvidenceSample {

    private EvidenceSample() {}

    /** Element {@code floor(i * size / n)} for {@code i} in {@code 0..n-1}; the whole list when it holds no more. */
    public static <T> List<T> spread(List<T> ordered, int n) {
        int size = ordered.size();
        if (size <= n) return List.copyOf(ordered);
        if (n <= 0) return List.of();
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(ordered.get((int) ((long) i * size / n)));
        }
        return List.copyOf(out);
    }
}
