// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.finding.FindingRow;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * A detector's adapter into the case object. Implementations answer one question — "what does a case
 * for this finding look like?" — and know nothing about case lifecycle, numbering or persistence.
 *
 * <p>This is the extension point the brief calls cause-neutrality. A new detector becomes a case
 * producer by implementing this interface and adding a {@code detector} constant; {@link CaseOpener},
 * the case page, close/absorb and the activity trail all pick it up with no further change.
 *
 * <p><b>Event-driven, not swept.</b> A case used to open from a periodic reconciler asking every source
 * "what is firing right now" and closing whatever a source stopped naming. Under the open/closed finding
 * model (decision 1) a ruling freezes the finding it landed on, so there is no live set left to sweep —
 * {@link CaseOpener} calls {@link #shape} once, at the moment a finding's ruling (machine or human)
 * qualifies it for a case, and {@link CaseLedger#openOrJoin} does the rest in that same transaction.
 */
public interface CaseSource {

    /** Whether this source is the one that shapes a case for a finding filed under {@code classifierKey}. */
    boolean owns(String classifierKey);

    /**
     * The case {@code finding} would open or join, in the detector-neutral shape {@link CaseOpener}
     * hands to {@link CaseLedger#openOrJoin}. Called only once {@link CaseOpener} has already decided
     * {@code finding} qualifies — a ruling landed positive, or (secret leak) the facet crossed high
     * confidence — so this method reads the finding's own numbers and never re-checks that gate itself.
     */
    CaseDetection shape(FindingRow finding);

    /**
     * The finding's onset, or null when it is blank or not an instant (Postgres's own timestamp rendering): the case
     * then opens unbracketed. Never substituted with now, since a case's onset is stamped once at open.
     */
    static @Nullable Instant onset(FindingRow finding) {
        String onset = finding.onsetAt();
        if (onset.isBlank()) return null;
        try {
            return Instant.parse(onset);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
