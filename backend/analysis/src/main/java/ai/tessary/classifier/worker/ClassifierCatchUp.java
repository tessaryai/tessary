// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierRow;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Population-level work for a per-span classifier, run only once its sweep reaches the head of the stream. Asked
 * mid-backlog, unchecked spans would read as clean and dilute a rate. Attaches by being a bean.
 */
public interface ClassifierCatchUp {

    Set<String> kinds();

    /**
     * @param checkedBefore every span stored before this was checked; one at or after it may not have been. Null when
     * the sweep never checked a span
     */
    void caughtUp(ClassifierJobRow job, ClassifierRow signal, @Nullable String checkedBefore);
}
