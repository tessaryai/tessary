// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One case-worthy finding, in the detector-neutral shape {@link CaseLedger#openOrJoin} takes. @param basis why this
 * crossed its own detector's bar, in that detector's terms; never normalize it away @param severity 0..1, ordering
 * only, never rendered; a case keeps the highest ever shaped @param onsetAt when the spell began; null means the
 * detector could not bracket it, never {@code now}; stamped once at open @param findingId never null: a case with no
 * finding has nothing anyone could act on
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
