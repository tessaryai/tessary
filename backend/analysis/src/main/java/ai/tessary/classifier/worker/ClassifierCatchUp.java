// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierRow;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Work a per-span classifier does over its detections as a population, once its sweep has checked every span
 * there is.
 *
 * <p>A per-span detector decides one span at a time; some claims are only true of many. Whether a call site's
 * outputs are failing their schema at a rate its own history does not predict is a question about every span
 * the sweep has checked there, so it has to wait until the sweep has checked them: asked mid-backlog, the
 * unchecked spans read as clean and dilute the rate. The worker calls this when a sweep reaches the head of the
 * stream, and never while it is still paging.
 *
 * <p>A classifier attaches by being a bean, as a {@link ClassifierSweep} does; the worker names none of them.
 */
public interface ClassifierCatchUp {

    /** The {@code signal.detector} kinds this runs for. */
    Set<String> kinds();

    /**
     * Run the population work for one classifier whose sweep just caught up.
     *
     * @param checkedBefore the sweep cursor's {@code created_at}: every span stored before it has been checked,
     *     and one stored at or after it may not have been. Null when the sweep has never checked a span
     */
    void caughtUp(ClassifierJobRow job, ClassifierRow signal, @Nullable String checkedBefore);
}
