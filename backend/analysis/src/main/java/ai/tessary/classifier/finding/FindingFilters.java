// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.catalog.BuiltInDetector;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The two narrowings the findings page applies that no SQL query can express, as pure functions.
 *
 * <p>They are extracted rather than inlined for one reason: they are the whole of what a merge across
 * {@link TriageSource}s can silently get wrong, and as statics they are testable without Spring, without
 * a database and therefore on any machine. {@code FindingServiceMergeTest} holds both.
 *
 * <p>Both were lifted verbatim out of {@code BehaviorDriftService.findings} when it was split. The
 * relative order is preserved with them: the flag-layer filter runs first, the SOP de-dup second, and
 * the conformance predicate decides whether the conformance source contributes at all.
 */
public final class FindingFilters {

    private FindingFilters() {}

    /**
     * The shared table's rows this org may see, minus the ones its own projection would duplicate.
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
     *
     * <p><b>The SOP de-dup filter.</b> Conformance rows live in the SAME table and are appended by their
     * own source, which is the only projection carrying {@code kind} — the drift/baseline distinction the
     * surface renders. Without this exclusion every SOP finding appears twice, once stripped of the one
     * field saying what claim it is making.
     */
    public static List<FindingRow> visible(List<FindingRow> rows, Set<String> unavailable) {
        return rows.stream()
                .filter(f -> !unavailable.contains(f.classifierKey()))
                .filter(f -> !BuiltInDetector.Kind.SOP_CONFORMANCE.equals(f.classifierKey()))
                .toList();
    }

    /**
     * Whether the conformance source contributes to this page at all.
     *
     * <p>Conformance findings carry no call site — an SOP rule is conversation-scoped — so any call-site
     * narrowing excludes them, and the detector filter includes them only for their own rail or for the
     * unfiltered page. The flag layer withholds them exactly as it withholds any other classifier's.
     */
    public static boolean conformanceApplies(
            @Nullable String callSiteId, @Nullable String detector, Set<String> unavailable) {
        return callSiteId == null
                && (detector == null || BuiltInDetector.Kind.SOP_CONFORMANCE.equals(detector))
                && !unavailable.contains(BuiltInDetector.Kind.SOP_CONFORMANCE);
    }
}
