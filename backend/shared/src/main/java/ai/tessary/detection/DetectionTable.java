// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import java.util.regex.Pattern;

/**
 * One classifier's detection table, registered as a Spring bean by whichever module owns the
 * classifier. {@link DetectionTableRegistry} folds every bean on the classpath into one lookup, so
 * the writer, the retention sweep and the query-side readers stop naming any table by literal
 * string.
 *
 * <p>{@link Grain} here is deliberately not {@code ClassifierModelModule.Grain} (in
 * {@code backend/analysis}): that enum answers "what does the sweep dispatch on", a scoring-time
 * question invisible from {@code backend/shared}. This one answers "what does {@code subject_kind}
 * say in the stitched union", a storage-shape question. Conflating the two would drag analysis's
 * scoring vocabulary into a module with no dependency on analysis at all.
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
