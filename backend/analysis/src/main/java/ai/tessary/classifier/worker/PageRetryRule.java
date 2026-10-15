// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.catalog.PagedDetector.PageAction;
import ai.tessary.classifier.catalog.PagedDetector.ScoredPage;

/**
 * A page where over half the sent turns failed on provider unavailability is not written: its cursor is held and the
 * page re-sent next tick, until {@code maxPageRetries} holds, after which it is skipped so an outage cannot stall the
 * sweep. At or under half, the page is written and failed turns are treated as ineligible.
 */
final class PageRetryRule {

    private PageRetryRule() {}

    /** @param pageRetries holds of this page so far */
    static PageAction decide(ScoredPage page, int pageRetries, int maxPageRetries) {
        return switch (page.status()) {
            case PAUSED -> PageAction.PASS;
            case ABORTED -> PageAction.ABORT;
            case SCORED -> {
                if (page.unavailable() * 2 <= page.sent()) yield PageAction.PERSIST;
                yield pageRetries < maxPageRetries ? PageAction.HOLD : PageAction.SKIP;
            }
        };
    }
}
