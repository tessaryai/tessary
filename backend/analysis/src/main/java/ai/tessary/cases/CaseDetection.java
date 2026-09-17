// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One case-worthy finding, in the detector-neutral shape {@link CaseOpener} hands to
 * {@link CaseLedger#openOrJoin}. A {@link CaseSource} shapes one of these from a single finding;
 * nothing downstream knows or cares which detector filed it.
 *
 * @param title the sentence a human reads first, written by the source ("Prospect qualification rigor
 *     fell 67 pts"). Sentences over metrics: it says what happened, not what the number is.
 * @param basis why this crossed <i>its own</i> detector's bar, in that detector's terms. The ranked
 *     list carries several detectors at once and they do not share a threshold, so this is where a
 *     reader learns whether "crossed" meant a sustained change against a frozen baseline or a
 *     configured limit. Never normalize it away.
 * @param severity 0..1, for ordering the list only. Comparable across detectors by construction and
 *     by nothing else — never render it as a number. A case's own severity is the highest ever shaped
 *     for it across every finding it holds ({@link CaseLedger#openOrJoin}); it only rises.
 * @param onsetAt when the spell began, not when it was noticed. CUSUM's change point; a classifier
 *     rule's quantized window start; the human verdict that made a finding's recurrences mean
 *     something. <b>Null means the detector could not bracket this spell</b>, and a source must say so
 *     rather than substituting {@code now}. A case's own onset is stamped once, at open, from the
 *     finding that opened it, and never moves after that — later findings that join only refresh the
 *     headline, not the onset.
 * @param findingId the finding this case is about, and never null: every source shapes exactly one
 *     finding at a time. A case with no finding behind it has no evidence, no ruling, no absorb verb
 *     and no RCA lane, so it was never a case anyone could act on; the archived rows that predate the
 *     reverse {@code finding.case_id} link stay finding-less on {@link CaseRow}, but nothing writes one.
 */
public record CaseDetection(
        CaseKey key,
        String subjectLabel,
        @Nullable String callSiteId,
        String findingId,
        String title,
        String basis,
        double severity,
        @Nullable Instant onsetAt,
        @Nullable Double currentValue,
        @Nullable Double baselineValue,
        @Nullable Double delta) {}
