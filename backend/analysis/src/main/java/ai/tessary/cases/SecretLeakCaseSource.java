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
 * A leaked credential is a fact to rotate, so a high-confidence leak skips triage: {@code ClassifierArming} rules it
 * positive and calls {@link CaseOpener} in the same transaction; low confidence goes through triage or a person. A leak
 * never recovers on its own, so the case stays open until a person resolves it. One case per (rule, call site) facet.
 */
@Component
public class SecretLeakCaseSource implements CaseSource {

    private final SecretLeakDetailService detail;

    public SecretLeakCaseSource(SecretLeakDetailService detail) {
        this.detail = detail;
    }

    @Override
    public boolean owns(String classifierKey) {
        return BuiltInDetector.Kind.SECRET_LEAK.equals(classifierKey);
    }

    @Override
    public CaseDetection shape(FindingRow finding) {
        SecretLeakDetail read = detail.detail(finding);
        if (read == null) {
            // CaseOpener only calls this for a positive ruling, so a failed read here is the detection table failing.
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
                // Severity 1 by construction: a high-confidence leak is an incident now and outranks every other
                // detector.
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
