// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.cases.CaseEventRepository;
import ai.tessary.cases.CaseEventRow;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.detector.groundedness.GroundednessAssessmentRepository;
import ai.tessary.classifier.detector.groundedness.GroundednessConfig;
import ai.tessary.classifier.detector.groundedness.GroundednessDetector;
import ai.tessary.classifier.frustration.FrustrationAssessmentRepository;
import ai.tessary.classifier.frustration.JevFrustrationQuestion;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RateClassifierFixture {

    private static final String FRUSTRATION_VERSION =
            JevFrustrationQuestion.scorerVersion(JevFrustrationQuestion.DEFAULT_THRESHOLD);
    private static final String GROUNDEDNESS_VERSION =
            GroundednessDetector.scorerVersion(GroundednessConfig.DEFAULT_THRESHOLD);

    private final TenantService tenants;
    private final CapabilityFixture capabilities;
    private final ClassifierService classifierService;
    private final ClassifierRepository classifiers;
    private final FrustrationAssessmentRepository frustrationAssessments;
    private final GroundednessAssessmentRepository groundednessAssessments;
    private final CaseEventRepository caseEvents;
    private final JdbcClient jdbc;

    @Autowired
    public RateClassifierFixture(
            TenantService tenants,
            CapabilityFixture capabilities,
            ClassifierService classifierService,
            ClassifierRepository classifiers,
            FrustrationAssessmentRepository frustrationAssessments,
            GroundednessAssessmentRepository groundednessAssessments,
            CaseEventRepository caseEvents,
            JdbcClient jdbc) {
        this.tenants = tenants;
        this.capabilities = capabilities;
        this.classifierService = classifierService;
        this.classifiers = classifiers;
        this.frustrationAssessments = frustrationAssessments;
        this.groundednessAssessments = groundednessAssessments;
        this.caseEvents = caseEvents;
        this.jdbc = jdbc;
    }

    public String project(String slug, Capability capability) {
        return TenantFixture.bootstrap(tenants, slug, org -> capabilities.grant(org.id(), capability))
                .project()
                .id();
    }

    public ClassifierRow builtIn(String pid, String classifierKey) {
        classifierService.seedBuiltIns(pid);
        return ClassifierRows.byKey(classifiers, pid, classifierKey).orElseThrow();
    }

    public void frustrationHours(
            String pid,
            ClassifierRow signal,
            String callSite,
            Instant start,
            int fromHour,
            int hours,
            int perHour,
            double rate) {
        long flaggedPerHour = Math.round(perHour * rate);
        for (int h = fromHour; h < fromHour + hours; h++) {
            for (int c = 0; c < perHour; c++) {
                String trace = callSite + "-" + h + "-" + String.format(Locale.ROOT, "%02d", c);
                boolean flagged = c < flaggedPerHour;
                Instant at = start.plus(Duration.ofHours(h)).plusSeconds(c);
                frustrationAssessment(pid, signal, trace, "conv-" + trace, callSite, at, flagged, FRUSTRATION_VERSION);
                if (flagged) frustrationFlag(pid, signal, trace, "conv-" + trace, callSite, at);
            }
        }
    }

    public void frustrationAssessment(
            String pid,
            ClassifierRow signal,
            String traceId,
            String conversation,
            @Nullable String callSite,
            Instant at,
            boolean frustrated,
            String version) {
        frustrationAssessments.insert(new FrustrationAssessmentRepository.Assessment(
                Ids.ulid(),
                pid,
                signal.id(),
                traceId,
                "span-" + traceId,
                conversation,
                callSite,
                at,
                frustrated,
                version,
                "TYPESAFE",
                "typesafe/jev-1.13-20260917",
                null,
                "{}",
                null,
                null,
                null));
    }

    private void frustrationFlag(
            String pid, ClassifierRow signal, String traceId, String conversation, String callSite, Instant at) {
        jdbc.sql("INSERT INTO frustration_detection"
                        + " (id, project_id, classifier_id, classifier_key, subject_session_id, subject_trace_id,"
                        + " severity, confidence, evidence, subject_started_at)"
                        + " VALUES (:id, :pid, :cid, 'frustration', :conv, :trace, 'warn', 'high',"
                        + " CAST(:evidence AS jsonb), :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("conv", conversation)
                .param("trace", traceId)
                .param("evidence", "{\"score\":0.71,\"call_site_id\":\"" + callSite + "\"}")
                .param("at", Timestamp.from(at))
                .update();
    }

    public void groundednessAnswer(
            String pid,
            ClassifierRow signal,
            String trace,
            String span,
            String callSite,
            Instant at,
            boolean flagged,
            String evidence) {
        groundednessAssessments.insert(new GroundednessAssessmentRepository.Assessment(
                Ids.ulid(),
                pid,
                signal.id(),
                null,
                trace,
                span,
                callSite,
                flagged ? 0.99 : 0.10,
                flagged,
                GROUNDEDNESS_VERSION,
                at.toString()));
        if (flagged) groundednessDetection(pid, signal, trace, span, at, evidence);
    }

    public void groundednessDetection(
            String pid, ClassifierRow signal, String trace, String span, Instant at, String evidence) {
        jdbc.sql("INSERT INTO groundedness_detection"
                        + " (id, project_id, classifier_id, classifier_key, subject_trace_id, subject_span_id,"
                        + " severity, confidence, evidence, subject_started_at)"
                        + " VALUES (:id, :pid, :cid, 'groundedness', :trace, :span, 'warn', 'high',"
                        + " CAST(:evidence AS jsonb), :at)")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("cid", signal.id())
                .param("trace", trace)
                .param("span", span)
                .param("evidence", evidence)
                .param("at", Timestamp.from(at))
                .update();
    }

    public String rcaReport(
            String pid,
            String findingId,
            String subjectKind,
            String subjectId,
            String subjectLabel,
            String metric,
            String reportKind,
            String causes) {
        String job = Ids.ulid();
        String report = Ids.ulid();
        String now = Instant.now().toString();
        jdbc.sql("INSERT INTO job (id, project_id, kind, status, payload, created_at, updated_at)"
                        + " VALUES (:id, :pid, 'rca', 'done', CAST('{}' AS jsonb), :now, :now)")
                .param("id", job)
                .param("pid", pid)
                .param("now", now)
                .update();
        jdbc.sql("INSERT INTO rca_report (id, project_id, job_id, subject_kind, subject_id, subject_label, metric,"
                        + " window_from, window_split, window_to, current_value, prior_value, delta, status,"
                        + " created_at, engine, finding_id, report_kind, causes)"
                        + " VALUES (:id, :pid, :job, :subjectKind, :subjectId, :subjectLabel, :metric,"
                        + " :now, :now, :now, 0, 0, 0, 'done', :now, 'agentic', :fid, :reportKind,"
                        + " CAST(:causes AS jsonb))")
                .param("id", report)
                .param("pid", pid)
                .param("job", job)
                .param("subjectKind", subjectKind)
                .param("subjectId", subjectId)
                .param("subjectLabel", subjectLabel)
                .param("metric", metric)
                .param("now", now)
                .param("fid", findingId)
                .param("reportKind", reportKind)
                .param("causes", causes)
                .update();
        return report;
    }

    public Map<String, Object> state(String detector, String pid, String callSite) {
        return jdbc.sql("SELECT baseline_calls, baseline_failures, reset_at, reset_note FROM " + detector + "_state"
                        + " WHERE project_id = :pid AND call_site_id = :cs")
                .param("pid", pid)
                .param("cs", callSite)
                .query()
                .singleRow();
    }

    public long clearedDetections(String detector, String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM " + detector + "_detection"
                        + " WHERE project_id = :pid AND cleared_at IS NOT NULL")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }

    public long detections(String detector, String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM " + detector + "_detection WHERE project_id = :pid")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }

    public String resolvedEventDetail(String pid, String caseId) {
        return caseEvents.listByCase(pid, caseId).stream()
                .filter(e -> CaseEventRow.Kind.RESOLVED.equals(e.kind()))
                .findFirst()
                .map(CaseEventRow::detail)
                .orElseThrow();
    }
}
