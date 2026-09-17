// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.secretleak.SecretLeakDetailService;
import ai.tessary.classifier.secretleak.SecretLeakEvidence;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakDetail;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Shapes a secret leak ruled positive into a case. A leaked credential is a fact to rotate, not a claim
 * for Layer 2 to audit, so a high-confidence one skips triage: {@code ClassifierArming} writes the positive
 * ruling itself and calls {@link CaseOpener} in the same transaction.
 *
 * <p><b>Confidence decides who rules.</b> {@code ClassifierArming} files a finding for every facet (one
 * rule at one call site) that crosses its window threshold. One recorded {@code high} on the finding, the
 * same word the finding page's subtitle shows, is ruled positive at arming; a low-confidence one stays
 * unruled and reaches a case only through triage or a person.
 *
 * <p><b>A leak never recovers on its own.</b> A credential that stopped appearing in output is still
 * exposed until someone rotates it, so nothing here ever closes the case on silence — it stays open
 * until a person resolves it, which closes the finding with it (see {@code CaseService#resolve}). A
 * later leak of the same facet is a fresh finding once this one is ruled or closed, and joins or reopens
 * from there exactly like any other cause.
 *
 * <p>One case per (rule, call site) facet, matching the finding's own scope: a leaked AWS key from one
 * call site and a leaked GitHub token from another are two credentials to rotate, so they never share a
 * case.
 */
@Component
public class SecretLeakCaseSource implements CaseSource {

    private final SecretLeakDetailService detail;

    public SecretLeakCaseSource(SecretLeakDetailService detail) {
        this.detail = detail;
    }

    @Override
    public String detector() {
        return CaseRow.Detector.SECRET_LEAK;
    }

    @Override
    public boolean owns(String classifierKey) {
        return BuiltInDetector.Kind.SECRET_LEAK.equals(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        SecretLeakDetail read = detail.detail(finding);
        if (read == null) {
            // CaseOpener only calls this once the finding is already ruled positive; an unreadable
            // detail read here is the finding's own detection table, not this decision, failing.
            throw new TessaryException(ClassifierError.FINDING_NOT_FOUND, finding.id());
        }
        return toDetection(finding, read);
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
