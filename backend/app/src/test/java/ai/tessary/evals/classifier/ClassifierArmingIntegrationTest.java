// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.finding.FindingEvidenceRepository;
import ai.tessary.evals.classifier.finding.FindingRepository;
import ai.tessary.evals.classifier.finding.FindingRow;
import ai.tessary.evals.classifier.worker.ClassifierArming;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.StubEncoderScorerConfig;
import ai.tessary.evals.testsupport.SubstrateV2Fixtures;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance for classifier arming — the replacement for the threshold {@code alert_rule} path.
 *
 * <p>The claim under test: N detections inside the configured window open exactly ONE finding, the
 * spans that fired are pinned under it as evidence, a re-sweep over the same window refreshes that one
 * finding rather than opening a second, and a classifier nobody armed files nothing at all.
 */
@SpringBootTest
@Import(StubEncoderScorerConfig.class)
class ClassifierArmingIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    ClassifierArming arming;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Test
    void armedWindowOpensOneFindingWithItsSpansAsEvidence_andReSweepingRefreshesIt() {
        String pid = TenantFixture.bootstrap(tenants, "arming-open").project().id();
        // Three in a day, armed at three: the bar is crossed exactly, which is the boundary that decides
        // whether "N" means "N" or "more than N".
        String classifierId = armedClassifier(pid, "secret_leak", "event_count", 3, 86_400);

        List<FindingEvidenceRepository.Ref> refs = writeDetections(pid, classifierId, "secret_leak", 3);
        String findingId = arming.evaluate(row(pid, classifierId, "secret_leak"), pid, refs, Instant.now());

        assertNotNull(findingId, "three detections against a bar of three arms the classifier");
        FindingRow finding = findings.findById(pid, findingId).orElseThrow();
        assertEquals("secret_leak", finding.classifierKey());
        assertEquals(FindingRow.SubjectKind.CLASSIFIER, finding.subjectKind());
        assertEquals(classifierId, finding.subjectId(), "the subject of a per-span finding is the classifier");
        assertEquals(3, finding.sampleCount());
        assertEquals(3, evidenceCount(findingId), "the spans that fired are pinned under the finding");

        // A second pass over the same window is a REFRESH, not a second finding: the count is assigned
        // from the window rather than accumulated, so an idempotent re-sweep reports the same number.
        String again = arming.evaluate(row(pid, classifierId, "secret_leak"), pid, refs, Instant.now());
        assertEquals(findingId, again, "re-sweeping the same window refreshes the one finding");
        assertEquals(1, liveFindings(pid), "and opens no second one");
        assertEquals(
                3,
                findings.findById(pid, findingId).orElseThrow().sampleCount(),
                "the count is what the window holds, not how often the sweep ran");
        assertEquals(3, evidenceCount(findingId), "and the evidence set does not grow on a repeat reference");
    }

    @Test
    void belowTheBarFilesNothing() {
        String pid = TenantFixture.bootstrap(tenants, "arming-under").project().id();
        String classifierId = armedClassifier(pid, "secret_leak", "event_count", 5, 86_400);

        List<FindingEvidenceRepository.Ref> refs = writeDetections(pid, classifierId, "secret_leak", 4);

        assertNull(arming.evaluate(row(pid, classifierId, "secret_leak"), pid, refs, Instant.now()));
        assertEquals(0, liveFindings(pid), "four detections against a bar of five is not a finding");
    }

    @Test
    void anUnarmedClassifierNeverFilesAnything() {
        String pid = TenantFixture.bootstrap(tenants, "arming-absent").project().id();
        // No arming block: the shipped default. The platform draws no bar on anyone's behalf.
        String classifierId = classifier(pid, "secret_leak", null);

        List<FindingEvidenceRepository.Ref> refs = writeDetections(pid, classifierId, "secret_leak", 25);

        assertNull(arming.evaluate(row(pid, classifierId, "secret_leak"), pid, refs, Instant.now()));
        assertEquals(0, liveFindings(pid), "twenty-five detections and no configured bar is still no finding");
    }

    @Test
    void distinctUsersCountsSessionsNotDetections() {
        String pid = TenantFixture.bootstrap(tenants, "arming-users").project().id();
        String classifierId = armedClassifier(pid, "secret_leak", "distinct_users", 3, 86_400);

        // Three detections, two sessions. The session basis is the user proxy the threshold rules used,
        // and it must keep counting people rather than events or the translated number changes meaning.
        String sessionA = SubstrateV2Fixtures.sessionId();
        String sessionB = SubstrateV2Fixtures.sessionId();
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        refs.add(writeOne(pid, classifierId, "secret_leak", sessionA));
        refs.add(writeOne(pid, classifierId, "secret_leak", sessionA));
        refs.add(writeOne(pid, classifierId, "secret_leak", sessionB));

        assertNull(
                arming.evaluate(row(pid, classifierId, "secret_leak"), pid, refs, Instant.now()),
                "two sessions is under a bar of three, however many detections they produced");
        assertTrue(detections.countInWindow(
                        "secret_leak",
                        pid,
                        classifierId,
                        Instant.now().minusSeconds(86_400).toString(),
                        Instant.now().plusSeconds(60).toString(),
                        false)
                >= 3);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /**
     * The classifier row, armed or not. An upsert because bootstrapping a project already seeds the
     * built-in catalog — this test is about the arming block on that row, not about creating a second
     * classifier under a key that is unique per project.
     */
    private String classifier(String pid, String key, @org.jspecify.annotations.Nullable String configJson) {
        return jdbc.sql("INSERT INTO classifier (id, project_id, classifier_key, name, detector, built_in, version,"
                        + " enabled, config_json, created_at, updated_at)"
                        + " VALUES (:id, :pid, :key, :key, :key, true, 1, true, :cfg, :now, :now)"
                        + " ON CONFLICT (project_id, classifier_key)"
                        + " DO UPDATE SET config_json = EXCLUDED.config_json, detector = EXCLUDED.detector,"
                        + " updated_at = EXCLUDED.updated_at RETURNING id")
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("key", key)
                .param("cfg", configJson)
                .param("now", Instant.now().toString())
                .query(String.class)
                .single();
    }

    private String armedClassifier(String pid, String key, String basis, int threshold, int windowSeconds) {
        return classifier(
                pid,
                key,
                "{\"arming\":{\"basis\":\"" + basis + "\",\"threshold\":" + threshold + ",\"window_seconds\":"
                        + windowSeconds + "}}");
    }

    private ClassifierRow row(String pid, String classifierId, String key) {
        String now = Instant.now().toString();
        return new ClassifierRow(
                classifierId,
                pid,
                key,
                key,
                /* description */ null,
                /* detector */ key,
                jdbc.sql("SELECT config_json FROM classifier WHERE id = :id")
                        .param("id", classifierId)
                        .query(String.class)
                        .optional()
                        .orElse(null),
                /* builtIn */ true,
                /* version */ 1,
                /* enabled */ true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now);
    }

    private List<FindingEvidenceRepository.Ref> writeDetections(String pid, String classifierId, String key, int n) {
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        for (int i = 0; i < n; i++) refs.add(writeOne(pid, classifierId, key, SubstrateV2Fixtures.sessionId()));
        return refs;
    }

    private FindingEvidenceRepository.Ref writeOne(String pid, String classifierId, String key, String sessionId) {
        String traceId = SubstrateV2Fixtures.traceId();
        String spanId = SubstrateV2Fixtures.spanId();
        detections.insert(
                Ids.ulid(), key, pid, classifierId, key, null, sessionId, traceId, spanId, "warn", "high", null);
        return FindingEvidenceRepository.Ref.span(traceId, spanId);
    }

    private long evidenceCount(String findingId) {
        return jdbc.sql("SELECT COUNT(*) FROM finding_evidence WHERE finding_id = :id")
                .param("id", findingId)
                .query(Long.class)
                .single();
    }

    private long liveFindings(String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM finding WHERE project_id = :pid AND status IN ('open','blocked')")
                .param("pid", pid)
                .query(Long.class)
                .single();
    }
}
