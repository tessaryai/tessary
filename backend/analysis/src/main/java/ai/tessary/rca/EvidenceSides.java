// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.FindingEvidenceRepository.SpanRef;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which of a finding's evidence rows are flagged and which are the comparison side. The one rule both
 * {@code checks.md} and the citable set read, so a cause can never cite rows the checks did not compare.
 *
 * @param roleCounts recorded rows per role, only the roles present
 * @param flaggedSessions every session a flagged row names, a trace row's included
 * @param flaggedSessionRefs the session-grain flagged rows, which the flagged count counts in session grain
 * @param shiftBaselineKeys in a shift, the row keys a {@code baseline} row names; a {@code member} row with one of them
 *     is a healthy row just before the change point, so it is comparison. Empty for other shapes.
 */
public record EvidenceSides(
        ClaimShape shape,
        boolean anyWitness,
        Map<String, Integer> roleCounts,
        Set<String> flaggedTraces,
        Set<String> flaggedSessions,
        Set<String> flaggedSessionRefs,
        Set<String> comparisonTraces,
        Set<String> comparisonSessions,
        String grain,
        boolean baselinePresent,
        Set<String> shiftBaselineKeys) {

    public static final String TRACES = "traces";
    public static final String SESSIONS = "sessions";

    public enum Side {
        FLAGGED,
        COMPARISON,
        NONE
    }

    /**
     * Each row once, and one flagged row per flagged unit. A comparison row that is also a flagged row is flagged only,
     * except a shift's pre-step row, which is comparison only.
     */
    public record Split(List<SpanRef> flagged, List<SpanRef> comparison) {}

    public static EvidenceSides of(ClaimShape shape, List<FindingEvidenceRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FindingEvidenceRow row : rows) counts.merge(row.role(), 1, Integer::sum);
        boolean anyWitness = counts.containsKey(Role.WITNESS);
        Set<String> shiftBaselineKeys = new HashSet<>();
        if (shape == ClaimShape.DISTRIBUTION_SHIFT) {
            for (FindingEvidenceRow row : rows) {
                if (Role.BASELINE.equals(row.role()))
                    shiftBaselineKeys.add(key(row.traceId(), row.spanId(), row.sessionId()));
            }
        }

        Set<String> flaggedTraces = new LinkedHashSet<>();
        Set<String> flaggedSessions = new LinkedHashSet<>();
        Set<String> flaggedSessionRefs = new LinkedHashSet<>();
        Set<String> comparisonTraces = new LinkedHashSet<>();
        Set<String> comparisonSessions = new LinkedHashSet<>();
        Set<String> baselineTraces = new LinkedHashSet<>();
        for (FindingEvidenceRow row : rows) {
            Side side = side(
                    shape,
                    anyWitness,
                    shiftBaselineKeys,
                    row.role(),
                    key(row.traceId(), row.spanId(), row.sessionId()));
            if (side == Side.FLAGGED) {
                if (row.traceId() != null) flaggedTraces.add(row.traceId());
                else if (row.sessionId() != null) flaggedSessionRefs.add(row.sessionId());
                if (row.sessionId() != null) flaggedSessions.add(row.sessionId());
            } else if (side == Side.COMPARISON) {
                if (row.traceId() != null) comparisonTraces.add(row.traceId());
                if (row.sessionId() != null) comparisonSessions.add(row.sessionId());
                if (Role.BASELINE.equals(row.role()) && row.traceId() != null) baselineTraces.add(row.traceId());
            }
        }
        // A trace on both sides is flagged: it is what the claim is about.
        comparisonTraces.removeAll(flaggedTraces);
        comparisonSessions.removeAll(flaggedSessions);
        baselineTraces.removeAll(flaggedTraces);
        return new EvidenceSides(
                shape,
                anyWitness,
                Collections.unmodifiableMap(counts),
                Collections.unmodifiableSet(flaggedTraces),
                Collections.unmodifiableSet(flaggedSessions),
                Collections.unmodifiableSet(flaggedSessionRefs),
                Collections.unmodifiableSet(comparisonTraces),
                Collections.unmodifiableSet(comparisonSessions),
                flaggedSessionRefs.isEmpty() ? TRACES : SESSIONS,
                !baselineTraces.isEmpty(),
                Collections.unmodifiableSet(shiftBaselineKeys));
    }

    /**
     * A shift writes the healthy rows just before its change point as both {@code member} and {@code baseline}; they
     * are comparison rows, never flagged.
     */
    private static Side side(
            ClaimShape shape, boolean anyWitness, Set<String> shiftBaselineKeys, String role, String key) {
        if (shape == ClaimShape.DISTRIBUTION_SHIFT && Role.MEMBER.equals(role) && shiftBaselineKeys.contains(key)) {
            return Side.COMPARISON;
        }
        if (flaggedRoles(anyWitness).contains(role)) return Side.FLAGGED;
        if (comparisonRoles(shape, anyWitness).contains(role)) return Side.COMPARISON;
        return Side.NONE;
    }

    /**
     * {@code exemplar} and {@code changepoint} are flagged when no witness exists, so a classifier that writes only
     * those is not read as flagging nothing.
     */
    private static Set<String> flaggedRoles(boolean anyWitness) {
        return anyWitness ? Set.of(Role.WITNESS) : Set.of(Role.MEMBER, Role.EXEMPLAR, Role.CHANGEPOINT);
    }

    /**
     * A rate rise compares against its unflagged members and any baseline; a shift against its baseline only, since
     * its members are the shifted rows; a presence event against nothing.
     */
    private static Set<String> comparisonRoles(ClaimShape shape, boolean anyWitness) {
        return switch (shape) {
            case RATE_RISE -> anyWitness ? Set.of(Role.BASELINE, Role.MEMBER) : Set.of(Role.BASELINE);
            case DISTRIBUTION_SHIFT -> Set.of(Role.BASELINE);
            case PRESENCE_EVENT -> Set.of();
        };
    }

    /** The side a role reads on; a row's own side is {@link #sideOf(SpanRef)}. */
    public Side sideOf(String role) {
        if (flaggedRoles(anyWitness).contains(role)) return Side.FLAGGED;
        if (comparisonRoles(shape, anyWitness).contains(role)) return Side.COMPARISON;
        return Side.NONE;
    }

    public Side sideOf(SpanRef row) {
        return side(shape, anyWitness, shiftBaselineKeys, row.role(), key(row));
    }

    /** In {@link Role#ALL} order. */
    public List<String> roles(Side side) {
        return Role.ALL.stream().filter(role -> sideOf(role) == side).toList();
    }

    public int recordedRows() {
        return roleCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int flaggedCount() {
        return SESSIONS.equals(grain) ? flaggedSessionRefs.size() : flaggedTraces.size();
    }

    /** Flagged roles first, so a row ceiling cuts the comparison side before the flagged one. */
    public List<String> readOrder() {
        List<String> order = new ArrayList<>();
        for (Side side : List.of(Side.FLAGGED, Side.COMPARISON)) {
            for (String role : roles(side)) {
                if (roleCounts.containsKey(role)) order.add(role);
            }
        }
        return order;
    }

    /**
     * Row grain, not id grain: a healthy span in a failing trace is a comparison row, while the failing span's own
     * {@code member} row is flagged.
     */
    public Split split(List<SpanRef> rows) {
        Set<String> wholeTraces = new HashSet<>();
        for (SpanRef row : rows) {
            if (sideOf(row) == Side.FLAGGED && row.traceId() != null && row.spanId() == null) {
                wholeTraces.add(row.traceId());
            }
        }
        Set<String> flaggedKeys = new HashSet<>();
        Map<String, SpanRef> flagged = new LinkedHashMap<>();
        for (SpanRef row : rows) {
            if (sideOf(row) != Side.FLAGGED) continue;
            String key = key(row);
            flaggedKeys.add(key);
            if (!folded(row, wholeTraces)) flagged.putIfAbsent(key, row);
        }
        Map<String, SpanRef> comparison = new LinkedHashMap<>();
        for (SpanRef row : rows) {
            String key = key(row);
            if (sideOf(row) == Side.COMPARISON && !flaggedKeys.contains(key)) comparison.putIfAbsent(key, row);
        }
        return new Split(List.copyOf(flagged.values()), List.copyOf(comparison.values()));
    }

    /**
     * A flagged row that names a unit another flagged row already stands for: a session's turn in session grain, and a
     * span of a trace that is flagged whole. The comparison side holds one row per unit, so the flagged side does too.
     */
    private boolean folded(SpanRef row, Set<String> wholeTraces) {
        if (SESSIONS.equals(grain)) return row.traceId() != null;
        return row.spanId() != null && wholeTraces.contains(row.traceId());
    }

    /** A span is named by its trace and span ids; the session on it is denormalised. */
    static String key(SpanRef row) {
        return key(row.traceId(), row.spanId(), row.sessionId());
    }

    private static String key(@Nullable String traceId, @Nullable String spanId, @Nullable String sessionId) {
        return traceId != null ? "t " + traceId + ' ' + spanId : "s " + sessionId;
    }
}
