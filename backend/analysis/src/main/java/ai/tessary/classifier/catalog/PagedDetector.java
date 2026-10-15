// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.catalog;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.detector.Detection;
import ai.tessary.classifier.substrate.SubstrateObservation;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A detector scoring a whole page against a provider on the org's own key, which can fail part way. {@link #score}
 * writes nothing; the worker decides ({@code PageRetryRule}) and {@link #complete} writes what that allows. It writes
 * its own rows, so {@link #detect} refuses. @param <P> the scored page, handed back to {@link #complete} unchanged
 */
public interface PagedDetector<P extends PagedDetector.ScoredPage> extends BuiltInDetector {

    /** Writes nothing but the classifier's pause. */
    P score(ClassifierRow signal, List<SubstrateObservation> turns);

    /** @return the detections this call newly inserted, in page order */
    List<FiredTurn> complete(ClassifierRow signal, P page, PageAction action, long durationMs);

    int maxPageRetries();

    @Override
    default Detection detect(SubstrateObservation obs, @Nullable String config) {
        throw new IllegalStateException(kind() + " scores whole pages through PagedDetector");
    }

    interface ScoredPage {

        Status status();

        int sent();

        /** Failed on a 429, a 5xx or a transport failure after every retry. */
        int unavailable();
    }

    enum Status {
        /** Every eligible turn was sent; some may have failed. */
        SCORED,
        /** Nothing was sent. */
        PAUSED,
        /** The provider refused the key, or none exists: the classifier is now paused. */
        ABORTED
    }

    enum PageAction {
        PERSIST,
        /** Leave the cursor and send the page again next tick. */
        HOLD,
        /** Advance past the page: it was held as often as allowed. */
        SKIP,
        /** Leave the cursor: the classifier paused mid-page. */
        ABORT,
        /** Advance: the classifier is paused, and what it skips stays skipped. */
        PASS
    }

    record FiredTurn(
            SubstrateObservation turn,
            String detectionId,
            @Nullable String severity) {}
}
