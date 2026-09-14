// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRepository.SurvivalGate;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
import ai.tessary.classifier.secretleak.SecretLeakEvidence;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakDetail;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * High-confidence secret leaks, opened with no triage gate. Design contract: the task's decision #1 —
 * a leaked credential is a fact to rotate, not a claim for Layer 2 to audit, so this is the one case
 * source in the slice that reads {@link SurvivalGate#NONE} instead of a ruling.
 *
 * <p><b>Confidence, not the gate, is the bar.</b> {@code ClassifierArming} already files a finding for
 * every facet (one rule at one call site) that crosses its window threshold, low-confidence matches
 * included, since the finding is also how the Classifiers page shows discovery-mode hits. This source
 * only opens a case for the subset {@link SecretLeakDetailService#detail} reports {@code high}
 * confidence — the same band {@code ClassifierArming.Config#highOnly} may already be counting on, and
 * the same word the finding page's subtitle shows.
 *
 * <p>One case per (rule, call site) facet, matching the finding's own scope: a leaked AWS key from one
 * call site and a leaked GitHub token from another are two credentials to rotate, so they never share a
 * case, and a rule the classifier stops seeing recovers on its own once its last witness ages past the
 * quiet window.
 */
@Component
public class SecretLeakCaseSource implements CaseSource {

    /** Bound on one pass's live set, mirroring the other sources. */
    private static final int LIVE_SET_CAP = 200;

    private static final List<String> SECRET_LEAK_CLASSIFIERS = List.of(BuiltInDetector.Kind.SECRET_LEAK);

    /**
     * How long a facet may go unrefreshed before its case counts as recovered. Twice
     * {@code ClassifierArming}'s own built-in default window (24h), the same margin the write side
     * gives a spell before starting a new one — approximate for a project that has re-armed the
     * classifier at a different window, generously rather than closing a live leak early.
     */
    private static final Duration QUIET_WINDOW = Duration.ofHours(48);

    private final FindingRepository findings;
    private final SecretLeakDetailService detail;

    public SecretLeakCaseSource(FindingRepository findings, SecretLeakDetailService detail) {
        this.findings = findings;
        this.detail = detail;
    }

    @Override
    public String detector() {
        return CaseRow.Detector.SECRET_LEAK;
    }

    @Override
    public List<CaseDetection> detect(String projectId) {
        String seenSince = Instant.now().minus(QUIET_WINDOW).toString();
        List<CaseDetection> out = new ArrayList<>();
        for (FindingRow finding : findings.listSurvivingAnalysis(
                projectId, SECRET_LEAK_CLASSIFIERS, SurvivalGate.NONE, seenSince, LIVE_SET_CAP)) {
            SecretLeakDetail read = detail.detail(finding);
            // Confidence is the bar here, not the gate: a low-confidence facet is still a legitimate
            // finding on the Classifiers page, but nothing a person needs paged on it for.
            if (read == null || !"high".equals(read.confidence())) continue;
            out.add(toDetection(finding, read));
        }
        return out;
    }

    private static CaseDetection toDetection(FindingRow finding, SecretLeakDetail read) {
        CaseKey key = new CaseKey(
                CaseRow.Detector.SECRET_LEAK,
                CaseRow.SubjectKind.SECRET_PATTERN,
                finding.causeKey(),
                SecretLeakEvidence.MEASURE);
        return new CaseDetection(
                key,
                read.rule(),
                finding.callSiteId(),
                finding.id(),
                FindingTitle.of(finding),
                basis(read),
                // Severity-1 by construction: a high-confidence credential leak is a security incident
                // happening now, not a shift to characterize, and it outranks every other detector's
                // ranked-list ordering by design.
                1.0,
                onset(finding),
                (double) read.leakCount(),
                null,
                null);
    }

    private static String basis(SecretLeakDetail read) {
        return String.format(
                Locale.ROOT,
                "%d output%s matched the %s rule at high confidence, from 1 call site. A single "
                        + "high-confidence match opens the finding.",
                read.leakCount(),
                read.leakCount() == 1 ? "" : "s",
                read.rule());
    }

    private static @Nullable Instant onset(FindingRow finding) {
        try {
            return Instant.parse(finding.onsetAt());
        } catch (Exception e) {
            return null;
        }
    }
}
