// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.catalog.PagedDetector.PageAction;
import ai.tessary.classifier.catalog.PagedDetector.ScoredPage;

/**
 * What the worker does with a page a {@link ai.tessary.classifier.catalog.PagedDetector} scored.
 *
 * <p>A page on which more than half of the sent turns failed because the provider was unavailable is
 * not written: its cursor is held and the whole page is sent again next tick, so an outage does not
 * record a page as mostly unscored. After {@code maxPageRetries} holds the page is skipped instead, so an
 * outage costs at most that many re-sends of one page rather than stalling the sweep. A page at or under
 * half is written as it stands: the turns that failed are not recorded, like an ineligible turn.
 */
final class PageRetryRule {

    private PageRetryRule() {}

    /**
     * @param pageRetries how many times this page has already been held
     * @param maxPageRetries how many holds are allowed before the page is skipped
     */
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
