// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import java.util.regex.Pattern;

/**
 * One classifier's detection table, registered by its owning module. {@link Grain} is the storage {@code subject_kind},
 * deliberately not {@code ClassifierModelModule.Grain}. @param detectorKind a {@code BuiltInDetector.Kind} constant, a
 * String because this module cannot see analysis @param table interpolated (never bound) into SQL, so validated below
 * @param clearable whether the table carries {@code cleared_at}; {@link DetectionTableSchemaCheck} refuses boot when it
 * is missing
 */
public record DetectionTable(
        String detectorKind, String table, Grain grain, boolean shownInTraceViews, boolean clearable) {

    private static final Pattern TABLE_NAME = Pattern.compile("^[a-z_][a-z0-9_]*$");

    /**
     * Validated at construction because the name is interpolated into SQL text, never bound. A bad name is a
     * programming error, so fail at boot.
     */
    public DetectionTable {
        if (!TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "detection table name must match " + TABLE_NAME.pattern() + ", was: " + table);
        }
    }

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
