// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.tenant.Ids;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Test lookups over a project's stored classifier rows. */
public final class ClassifierRows {

    private ClassifierRows() {}

    /** The project's row for {@code classifierKey}, if it has one. */
    public static Optional<ClassifierRow> byKey(ClassifierRepository rows, String projectId, String classifierKey) {
        return rows.listByProject(projectId).stream()
                .filter(r -> r.classifierKey().equals(classifierKey))
                .findFirst();
    }

    public static String insert(
            ClassifierRepository rows,
            String projectId,
            String classifierKey,
            String name,
            String detector,
            @Nullable String configJson) {
        return insertRow(rows, projectId, classifierKey, name, detector, configJson, ClassifierRow.Mode.DISCOVERY, true)
                .id();
    }

    public static ClassifierRow insertRow(
            ClassifierRepository rows,
            String projectId,
            String classifierKey,
            String name,
            String detector,
            @Nullable String configJson,
            String mode,
            boolean enabled) {
        return write(rows, Ids.ulid(), projectId, classifierKey, name, detector, configJson, mode, enabled);
    }

    public static String insertKeyedByName(ClassifierRepository rows, String projectId, String name, String detector) {
        String id = Ids.ulid();
        return write(rows, id, projectId, name + "-" + id, name, detector, null, ClassifierRow.Mode.DISCOVERY, true)
                .id();
    }

    private static ClassifierRow write(
            ClassifierRepository rows,
            String id,
            String projectId,
            String classifierKey,
            String name,
            String detector,
            @Nullable String configJson,
            String mode,
            boolean enabled) {
        String now = Instant.now().toString();
        ClassifierRow row = new ClassifierRow(
                id,
                projectId,
                classifierKey,
                name,
                null,
                detector,
                configJson,
                false,
                1,
                enabled,
                mode,
                now,
                now,
                null);
        rows.insert(row);
        return row;
    }
}
