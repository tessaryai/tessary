// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge.model;

import ai.tessary.endusers.model.AliasVia;
import ai.tessary.endusers.model.PageState;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything the judge reads, loaded once from the fact tables for the judge window. A user ID is its key index: the
 * position of its {@link IdInfo} in {@code ids}; no ID text and no user key is here. Days are UTC day numbers.
 *
 * @param asOfDay the last closed UTC day, {@code D}
 * @param t0 the stored history start day, never the start of the window
 * @param horizonDays how long facts are kept; null when there is no limit
 * @param storedRouteClasses the classes the learn step stored
 * @param outageDays days on which every route fell under the outage share of its 28-day median
 * @param gapDays days whose traces were deleted before they were folded
 * @param pageStateMemory the rule states of the last two runs, before the memory was applied, oldest first
 */
public record Facts(
        int asOfDay,
        int t0,
        @Nullable Integer horizonDays,
        List<IdInfo> ids,
        IdDays idDays,
        List<NullDay> nullDays,
        List<AliasEdge> aliasEdges,
        List<Version> versions,
        List<IssueInput> issues,
        List<HitRow> hits,
        RouteClasses storedRouteClasses,
        List<Integer> outageDays,
        List<Integer> gapDays,
        List<PageState> pageStateMemory) {

    /**
     * One stored user ID. The shape is kept after the ID text is deleted.
     *
     * @param cluster the account cluster number; null when the ID names no cluster
     * @param sessions sessions the ID was seen in
     * @param sessionTokenLink every session of the ID carries the ID's text in its session ID
     */
    public record IdInfo(
            String shapePrefix,
            int shapeLength,
            @Nullable Integer cluster,
            int firstDay,
            int lastDay,
            int sessions,
            boolean sessionTokenLink) {}

    /**
     * The {@code end_user_day} rows as parallel columns, one index per row, ordered by key index, day, route number and
     * version number. Slot {@code s} (the 15-minute slot of the day, 0 to 95) is bit {@code s} of {@code slotMaskLow}
     * for {@code s < 64}, else bit {@code s - 64} of {@code slotMaskHigh}. Hour {@code h} is bit {@code h} of
     * {@code hourMask}. {@code sessionHashes} holds the 32 smallest, {@code jobKeys} at most 32.
     */
    public record IdDays(
            int[] id,
            int[] day,
            int[] routeNo,
            int[] versionNo,
            int[] traces,
            int[] tracesWithInput,
            int[] tracesWithParent,
            int[] hourMask,
            long[] slotMaskLow,
            int[] slotMaskHigh,
            int[][] sessionHashes,
            long[][] jobKeys) {}

    /** All traces and the traces with no user ID of one day, route and version. */
    public record NullDay(int day, int routeNo, int versionNo, int traces, int tracesNoUserId) {}

    /** A session or thread in which {@code fromId}'s last trace is before {@code toId}'s first. */
    public record AliasEdge(int fromId, int toId, AliasVia via, int day) {}

    /** {@code firstTraceDay} is read as the deploy day. */
    public record Version(int versionNo, @Nullable String projectVersionId, int firstTraceDay) {}

    /**
     * One issue: a case lineage with its findings.
     *
     * @param complete every finding's detector records every hit
     * @param metricShift every finding of the issue is a metric shift and none holds a hit row, so the issue has no
     *     user IDs hit to count; a metric shift with hit rows is measured like every other issue
     */
    public record IssueInput(
            String lineageKey,
            @Nullable String caseId,
            List<String> findingIds,
            boolean complete,
            boolean metricShift) {}

    /**
     * Refs of one issue that count as hits under the detector's hit rule, on one day.
     *
     * @param issue the index of the issue in {@code issues}
     * @param id the key index; null for refs with no user ID
     * @param routeNo the route of the ref's trace; null for a ref to a session only
     * @param sessionRoutes for a ref to a session only: traces of the session per route number; else empty
     */
    public record HitRow(
            int issue,
            @Nullable Integer id,
            int day,
            @Nullable Integer routeNo,
            Map<Integer, Integer> sessionRoutes,
            int refs) {}
}
