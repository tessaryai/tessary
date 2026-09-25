// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * An open, unruled {@link FindingRow} for unit tests, with only the columns a test names set away from a
 * plain default. The record has 28 components; a test reads better when it spells out the three that matter.
 */
public final class FindingRowBuilder {

    private String id = "fnd-1";
    private String projectId = "proj-1";
    private final String classifierKey;
    private String causeKey = "cause";
    private String subjectKind = FindingRow.SubjectKind.CLASSIFIER;
    private String subjectId = "sig-1";
    private @Nullable String subjectLabel;
    private @Nullable String callSiteId;
    private String status = FindingRow.Status.OPEN;
    private String onsetAt = "2026-09-01T00:00:00Z";
    private String lastSeenAt = "2026-09-02T00:00:00Z";
    private @Nullable String basis;
    private @Nullable Double severity;
    private long sampleCount = 12;
    private @Nullable String payloadJson;
    private @Nullable String triageVerdict;
    private @Nullable String humanVerdictAt;

    private FindingRowBuilder(String classifierKey) {
        this.classifierKey = classifierKey;
    }

    public static FindingRowBuilder of(String classifierKey) {
        return new FindingRowBuilder(classifierKey);
    }

    public FindingRowBuilder id(String value) {
        this.id = value;
        return this;
    }

    public FindingRowBuilder projectId(String value) {
        this.projectId = value;
        return this;
    }

    public FindingRowBuilder causeKey(String value) {
        this.causeKey = value;
        return this;
    }

    public FindingRowBuilder subject(String kind, String subject) {
        this.subjectKind = kind;
        this.subjectId = subject;
        return this;
    }

    public FindingRowBuilder subjectLabel(@Nullable String value) {
        this.subjectLabel = value;
        return this;
    }

    public FindingRowBuilder callSiteId(@Nullable String value) {
        this.callSiteId = value;
        return this;
    }

    public FindingRowBuilder status(String value) {
        this.status = value;
        return this;
    }

    public FindingRowBuilder onsetAt(String value) {
        this.onsetAt = value;
        return this;
    }

    public FindingRowBuilder lastSeenAt(String value) {
        this.lastSeenAt = value;
        return this;
    }

    public FindingRowBuilder basis(@Nullable String value) {
        this.basis = value;
        return this;
    }

    public FindingRowBuilder severity(@Nullable Double value) {
        this.severity = value;
        return this;
    }

    public FindingRowBuilder sampleCount(long value) {
        this.sampleCount = value;
        return this;
    }

    public FindingRowBuilder payload(@Nullable String value) {
        this.payloadJson = value;
        return this;
    }

    public FindingRowBuilder triageVerdict(@Nullable String value) {
        this.triageVerdict = value;
        return this;
    }

    public FindingRowBuilder humanVerdictAt(@Nullable String value) {
        this.humanVerdictAt = value;
        return this;
    }

    public FindingRow build() {
        return new FindingRow(
                id,
                projectId,
                classifierKey,
                causeKey,
                subjectKind,
                subjectId,
                subjectLabel,
                callSiteId,
                status,
                onsetAt,
                lastSeenAt,
                null,
                basis,
                severity,
                sampleCount,
                payloadJson,
                null,
                null,
                null,
                triageVerdict,
                triageVerdict == null ? null : FindingRow.TriageAction.of(triageVerdict),
                null,
                null,
                triageVerdict == null ? null : "2026-09-02T00:00:00Z",
                humanVerdictAt,
                null,
                "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z");
    }
}
