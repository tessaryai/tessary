// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.List;
import java.util.Set;

/**
 * The narrowing the findings page applies that no SQL query can express, as a pure function.
 *
 * <p>It is extracted rather than inlined for one reason: it is the whole of what a merge across
 * {@link TriageSource}s can silently get wrong, and as a static it is testable without Spring, without
 * a database and therefore on any machine. {@code FindingServiceMergeTest} holds it.
 *
 * <p>It was lifted verbatim out of {@code BehaviorDriftService.findings} when it was split.
 */
public final class FindingFilters {

    private FindingFilters() {}

    /**
     * The shared table's rows this org may see.
     *
     * <p><b>The flag-layer filter.</b> A finding belongs to the classifier that wrote it, so an org that
     * cannot see the classifier cannot see its leads either — otherwise hiding a row from the Detectors
     * list leaves its causes sitting in the Findings list below it, on the same page. The row's detector
     * is derived through its own {@code classifier_key}, exactly as the repository's own filter does.
     *
     * <p>Filtered in Java rather than in SQL, unlike the gate and the detector filter beside it, because
     * this narrows by ORG capability rather than by anything the query can express. The cost is that a
     * withheld classifier's rows still count against the page limit — acceptable, since the case only
     * arises after a flag is turned off on a project that already had the classifier running.
     */
    public static List<FindingRow> visible(List<FindingRow> rows, Set<String> unavailable) {
        return rows.stream()
                .filter(f -> !unavailable.contains(f.classifierKey()))
                .toList();
    }
}
