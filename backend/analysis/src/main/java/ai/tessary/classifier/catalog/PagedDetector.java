// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.substrate.SubstrateObservation;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A detector that scores a whole sweep page against a provider on the org's own key, and so can fail
 * part way in ways a local detector cannot: the key can be refused, and the provider can be down for
 * most of a page. The worker splits the page in two for it. {@link #score} makes the calls and writes
 * nothing; the worker decides from the result whether the page is persisted, held for the next tick,
 * skipped, or abandoned ({@code PageRetryRule}); {@link #complete} then writes what that decision
 * allows and returns the detections it newly inserted.
 *
 * <p>Such a detector writes its own rows, because what it records per page is more than a detection
 * per fired observation. {@link #detect} is not its path and refuses.
 *
 * @param <P> the detector's own scored page, handed back to {@link #complete} unchanged
 */
public interface PagedDetector<P extends PagedDetector.ScoredPage> extends BuiltInDetector {

    /** Score {@code turns} for {@code signal}, writing nothing but the classifier's pause. */
    P score(ClassifierRow signal, List<SubstrateObservation> turns);

    /**
     * Write what {@code action} allows for a page {@link #score} returned, and log the page.
     *
     * @return the detections this call newly inserted, in page order
     */
    List<FiredTurn> complete(ClassifierRow signal, P page, PageAction action, long durationMs);

    /** How many times a page may be held before it is skipped. */
    int maxPageRetries();

    @Override
    default Detection detect(SubstrateObservation obs, @Nullable String config) {
        throw new IllegalStateException(kind() + " scores whole pages through PagedDetector");
    }

    /** What a page's scoring came to, before anything is written. */
    interface ScoredPage {

        Status status();

        /** Turns sent to the provider. */
        int sent();

        /** Sent turns whose call failed on a 429, a 5xx or a transport failure after every retry. */
        int unavailable();
    }

    /** How {@link PagedDetector#score} ended. */
    enum Status {
        /** Every eligible turn was sent; some may have failed. */
        SCORED,
        /** The classifier was already paused: nothing was sent. */
        PAUSED,
        /** The provider refused the key, or no key exists, part way: the classifier is now paused. */
        ABORTED
    }

    /** What the worker does with a scored page. */
    enum PageAction {
        /** Write the page and advance the cursor past it. */
        PERSIST,
        /** Write nothing, leave the cursor, and send the page again next tick. */
        HOLD,
        /** Write nothing and advance past the page: it was held as often as allowed. */
        SKIP,
        /** Write nothing and leave the cursor: the classifier paused mid-page. */
        ABORT,
        /** Write nothing and advance: the classifier is paused, and what it skips stays skipped. */
        PASS
    }

    /**
     * A detection the page newly inserted.
     *
     * @param turn the observation it is about
     * @param detectionId the new row's id
     */
    record FiredTurn(
            SubstrateObservation turn,
            String detectionId,
            @Nullable String severity) {}
}
