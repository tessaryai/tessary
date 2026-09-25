// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import java.util.Optional;

/** Test lookups over a project's stored classifier rows. */
public final class ClassifierRows {

    private ClassifierRows() {}

    /** The project's row for {@code classifierKey}, if it has one. */
    public static Optional<ClassifierRow> byKey(ClassifierRepository rows, String projectId, String classifierKey) {
        return rows.listByProject(projectId).stream()
                .filter(r -> r.classifierKey().equals(classifierKey))
                .findFirst();
    }
}
