// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.finding.FindingRow;

/**
 * A detector's adapter into the case object: what a case for this finding looks like, with no knowledge of lifecycle,
 * numbering or persistence. A new detector implements this and adds a {@code detector} constant. {@link CaseOpener}
 * calls {@link #shape} once, when a finding's ruling qualifies it, and {@link CaseLedger#openOrJoin} does the rest in
 * the same transaction.
 */
public interface CaseSource {

    boolean owns(String classifierKey);

    /** Called only after {@link CaseOpener} decided {@code finding} qualifies, so this never re-checks that gate. */
    CaseDetection shape(FindingRow finding);
}
