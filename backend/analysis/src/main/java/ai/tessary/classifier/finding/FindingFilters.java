// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.List;
import java.util.Set;

/**
 * The narrowing no SQL can express, as a pure static so a merge across {@link TriageSource}s is testable without Spring
 * or a database.
 */
public final class FindingFilters {

    private FindingFilters() {}

    /**
     * An org that cannot see a classifier cannot see its findings either. In Java because it narrows by org capability;
     * a withheld classifier's rows still count against the page limit.
     */
    public static List<FindingRow> visible(List<FindingRow> rows, Set<String> unavailable) {
        return rows.stream()
                .filter(f -> !unavailable.contains(f.classifierKey()))
                .toList();
    }
}
