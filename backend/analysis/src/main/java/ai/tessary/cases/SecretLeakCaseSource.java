// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
import ai.tessary.classifier.secretleak.SecretLeakEvidence;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * High-confidence secret leaks, opened with no triage gate. A leaked credential is a fact to rotate, not
 * a claim for Layer 2 to audit, so this is the one case source that reads no ruling.
 *
 * <p><b>Confidence, not the gate, is the bar.</b> {@code ClassifierArming} already files a finding for
 * every facet (one rule at one call site) that crosses its window threshold, low-confidence matches
 * included, since the finding is also how the Classifiers page shows discovery-mode hits. This source
 * only opens a case for the subset recorded {@code high} on the finding itself, the same word the
 * finding page's subtitle shows.
 *
 * <p><b>A leak never recovers on its own.</b> A credential that stopped appearing in output is still
 * exposed until someone rotates it, so the live set is every live high-confidence finding: no recency
 * window and no cap, either of which would drop a facet out of the set and let the reconciler close its
 * case as recovered. That also covers a leak discovered long after it happened, which arming files on
 * event time. The case closes when a person resolves it, which resolves the finding with it (see
 * {@code CaseService#resolve}); a later leak of the same facet files a new finding and reopens or
 * opens a case from there.
 *
 * <p>One case per (rule, call site) facet, matching the finding's own scope: a leaked AWS key from one
 * call site and a leaked GitHub token from another are two credentials to rotate, so they never share a
 * case.
 */
@Component
public class SecretLeakCaseSource implements CaseSource {

    private static final List<String> SECRET_LEAK_CLASSIFIERS = List.of(BuiltInDetector.Kind.SECRET_LEAK);

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
        List<CaseDetection> out = new ArrayList<>();
        for (FindingRow finding : findings.listOpen(projectId, SECRET_LEAK_CLASSIFIERS)) {
            // A low-confidence facet is still a legitimate finding on the Classifiers page, but nothing a
            // person needs paged on it for. Checked before the detail read, which costs three queries.
            if (!finding.highConfidence()) continue;
            SecretLeakDetail read = detail.detail(finding);
            if (read == null) continue;
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
