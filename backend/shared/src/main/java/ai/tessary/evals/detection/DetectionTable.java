// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.detection;

import java.util.regex.Pattern;

/**
 * One classifier's detection table, registered as a Spring bean by whichever module owns the
 * classifier — the open {@code OpenDetectionTables} configuration for the four open kinds, and one
 * {@code @Bean} per paid classifier module (groundedness, behaviour-drift, frustration) for the
 * rest. {@link DetectionTableRegistry} folds every bean on the classpath into one lookup, so the
 * writer, the retention sweep and the four query-side readers stop naming any of the six tables by
 * literal string.
 *
 * <p><b>{@link Grain} here is deliberately NOT {@code ClassifierModelModule.Grain}</b> (in
 * {@code backend/analysis}, {@code OBSERVATION}/{@code TURN}/{@code TRACE}/{@code WINDOW}) — that
 * enum answers "what does the sweep dispatch on", a scoring-time question invisible from
 * {@code backend/shared}. This one answers "what does {@code subject_kind} say in the stitched
 * union" — a storage-shape question the two open-per-span/paid-per-trace grains the six tables
 * actually use answer completely. Conflating the two would drag analysis's scoring vocabulary into
 * a module with no dependency on analysis at all.
 *
 * @param detectorKind one of {@code BuiltInDetector.Kind}'s constants (a plain {@code String} here
 *     because {@code backend/shared} has no dependency on {@code backend/analysis}, where that enum
 *     lives)
 * @param table the physical table name, interpolated (never bound) into SQL by both the writer and
 *     {@link DetectionTableRegistry#unionSql()} — validated below so only an identifier the
 *     registering module itself chose can ever reach a query string
 * @param grain the {@code subject_kind} literal this classifier's rows carry in the stitched union
 */
public record DetectionTable(String detectorKind, String table, Grain grain) {

    private static final Pattern TABLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /**
     * Validates {@code table} at construction, not at first use. This name is interpolated straight
     * into SQL text by {@link DetectionTableRegistry#unionSql()} and by
     * {@code ClassifierDetectionWriteRepository}'s insert/read statements, never passed as a bind
     * parameter — the same reasoning that repository's own comment already gives for why the name is
     * interpolated in the first place. A bean registering a hostile or malformed table name is a
     * programming error in a module that ships with the platform, not user input, so failing loudly
     * at bean construction (mirroring {@code LeasePolicy}'s throwing compact constructor, in this
     * same module) is the right place to catch it — long before any query runs.
     */
    public DetectionTable {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "detection table name must match " + TABLE_NAME.pattern() + ", was: " + table);
        }
    }

    /** The {@code subject_kind} a detection table's rows are stitched into the union under. */
    public enum Grain {
        SPAN("span"),
        TRACE("trace");

        private final String subjectKind;

        Grain(String subjectKind) {
            this.subjectKind = subjectKind;
        }

        public String subjectKind() {
            return subjectKind;
        }
    }
}
