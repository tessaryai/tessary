// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.FacetWindow;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.FiredFacet;
import ai.tessary.classifier.ClassifierDetectionWriteRepository.SpanKey;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.retention.RetentionResolver;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Malformed output and the secret-leak facet keep 50 witnesses, so their hits come from the detection tables. The hits
 * must equal the detector's own count under the detector's own filters; a hit the detector never counted, or a counted
 * output left out, biases the contrast, and the issue then stays "hits not complete".
 */
@SpringBootTest
record DetectionHitsTest(
        TenantService tenants,
        JdbcClient jdbc,
        EndUserHitRepository hits,
        FindingHitRebuilder rebuilder,
        EndUserProjectRepository projects,
        RetentionResolver retention,
        HitReads reads,
        MalformedOutputRateRepository malformedRates,
        ClassifierDetectionWriteRepository detections,
        ClassifierRepository classifiers) {

    private static final Instant NOW = Instant.parse("2026-06-15T03:00:00Z");
    private static final Instant ONSET = Instant.parse("2026-06-14T17:00:00Z");

    @Test
    void malformed_hitsEqualDetectorCount() {
        String pid =
                TenantFixture.bootstrap(tenants, "hits-malformed").project().id();
        HitSeeds seed = new HitSeeds(jdbc, pid);
        Instant cursor = NOW.minus(Duration.ofHours(1));
        String classifierId = ClassifierRows.insertKeyedByName(classifiers, pid, "malformed", "malformed_output");
        seed.callSite("cs-a", "{\"type\":\"object\"}");
        seed.callSite("cs-b", null);
        seed.classifierCursor(classifierId, cursor);
        seed.finding(
                "f-mal",
                "malformed_output",
                "{\"cause_kind\":\"malformed_rate\",\"native_cause_key\":\"cs-a\",\"call_site_id\":\"cs-a\"}",
                classifierId,
                "cs-a",
                ONSET.toString(),
                NOW.toString());
        for (int i = 0; i < 80; i++) {
            Instant at = ONSET.plus(Duration.ofMinutes(i));
            failing(seed, classifierId, "m" + i, "user-" + (i % 20), "cs-a", at, at.plusSeconds(1), "not json");
        }
        for (int i = 0; i < 5; i++) {
            Instant at = ONSET.plus(Duration.ofMinutes(i));
            failing(seed, classifierId, "noschema" + i, "user-1", "cs-b", at, at.plusSeconds(1), "not json");
            failing(seed, classifierId, "empty" + i, "user-1", "cs-a", at, at.plusSeconds(1), "");
            failing(seed, classifierId, "late" + i, "user-1", "cs-a", at, cursor.plusSeconds(60 + i), "not json");
        }
        for (int i = 0; i < 3; i++) {
            seed.evidence("f-mal", "witness", "t-m" + i, "s-m" + i, NOW.toString());
        }

        sweep(pid);
        long detectorCount = malformedRates.hourlyTallies(pid, classifierId, ONSET, cursor.toString()).stream()
                .filter(t -> "cs-a".equals(t.toolKey()))
                .mapToLong(t -> t.failures())
                .sum();
        HitReads.FindingHits read = reads.findingHits(pid, "f-mal");

        assertAll(
                () -> assertEquals(80, detectorCount, "the seeded failing outputs the detector counts"),
                () -> assertEquals(80, read.hitRefs()),
                () -> assertEquals(detectorCount, read.hitRefs()),
                () -> assertEquals(20, read.hits().size(), "20 user IDs on one day and route"),
                () -> assertEquals(
                        List.of("detection"),
                        read.hits().stream()
                                .map(EndUserHitRepository.HitRow::source)
                                .distinct()
                                .toList()),
                () -> assertEquals(List.of("80|0|0|true|malformed_output"), summary(pid, "f-mal")));
    }

    @Test
    void secretLeakFacet_hitsEqualDetectorCount() {
        String pid =
                TenantFixture.bootstrap(tenants, "hits-secret-leak").project().id();
        HitSeeds seed = new HitSeeds(jdbc, pid);
        long window = 3600;
        String classifierId = ClassifierRows.byKey(classifiers, pid, "secret_leak")
                .orElseThrow()
                .id();
        classifiers.updateConfig(
                pid, classifierId, "{\"arming\":{\"threshold\":1,\"window_seconds\":3600,\"confidence\":\"high\"}}");
        Instant lastSeen = ONSET.plus(Duration.ofMinutes(170));
        Instant windowEnd = ONSET.plus(Duration.ofHours(3));
        seed.finding(
                "f-leak",
                "secret_leak",
                "{\"cause_kind\":\"armed_window\",\"native_cause_key\":\"pattern_a\",\"facet\":\"pattern_a\","
                        + "\"call_site_id\":\"cs-a\",\"window_seconds\":3600}",
                classifierId,
                "cs-a",
                ONSET.toString(),
                lastSeen.toString());
        List<SpanKey> spans = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            spans.add(leak(seed, classifierId, "a" + i, ONSET.plus(Duration.ofSeconds(145L * i)), "pattern_a", "high"));
        }
        for (int i = 0; i < 20; i++) {
            spans.add(leak(seed, classifierId, "g" + i, ONSET.plus(Duration.ofMinutes(i)), "pattern_b", "high"));
        }
        for (int i = 0; i < 5; i++) {
            spans.add(leak(seed, classifierId, "low" + i, ONSET.plus(Duration.ofMinutes(i)), "pattern_a", "low"));
            spans.add(
                    leak(seed, classifierId, "after" + i, windowEnd.plus(Duration.ofMinutes(i)), "pattern_a", "high"));
        }

        sweep(pid);
        List<FiredFacet> fired =
                detections.firedFacets("secret_leak", pid, classifierId, spans, "pattern", window, true);
        long detectorCount =
                detections
                        .countFacetWindows("secret_leak", pid, classifierId, "pattern", window, true, false, fired)
                        .stream()
                        .filter(w -> "pattern_a".equals(w.facet()) && "cs-a".equals(w.callSiteId()))
                        .filter(w -> w.windowStartEpochSecond() >= ONSET.getEpochSecond()
                                && w.windowStartEpochSecond() < windowEnd.getEpochSecond())
                        .mapToLong(FacetWindow::observed)
                        .sum();

        assertAll(
                () -> assertEquals(70, detectorCount, "the finding's pattern in its windows, at high confidence"),
                () -> assertEquals(70, reads.findingHits(pid, "f-leak").hitRefs()),
                () -> assertEquals(
                        detectorCount, reads.findingHits(pid, "f-leak").hitRefs()),
                () -> assertEquals(List.of("70|0|0|true|secret_leak"), summary(pid, "f-leak")));
    }

    private void failing(
            HitSeeds seed,
            String classifierId,
            String id,
            String userId,
            String callSite,
            Instant started,
            Instant stored,
            String output) {
        seed.tracedSpan("t-" + id, "s-" + id, userId, callSite, started, stored, output);
        detections.insert(
                Ids.ulid(),
                "malformed_output",
                seed.pid(),
                classifierId,
                "malformed_output",
                null,
                null,
                "t-" + id,
                "s-" + id,
                "warn",
                "high",
                "{\"reason\":\"not_json\",\"violations\":[]}");
    }

    private SpanKey leak(
            HitSeeds seed, String classifierId, String id, Instant started, String pattern, String confidence) {
        seed.tracedSpan("t-" + id, "s-" + id, "user-" + id, "cs-a", started, started.plusSeconds(1), "out");
        detections.insert(
                Ids.ulid(),
                "secret_leak",
                seed.pid(),
                classifierId,
                "secret_leak",
                null,
                null,
                "t-" + id,
                "s-" + id,
                "critical",
                confidence,
                "{\"pattern\":\"" + pattern + "\"}");
        return new SpanKey("t-" + id, "s-" + id);
    }

    private void sweep(String pid) {
        new HitSweepService(hits, rebuilder, projects, retention, Clock.fixed(NOW, ZoneOffset.UTC)).sweep(pid);
    }

    private List<String> summary(String pid, String findingId) {
        return jdbc.sql("""
                SELECT refs_total || '|' || refs_no_user_id || '|' || session_only_refs || '|' || complete || '|'
                       || hit_rule
                  FROM end_user_hit_summary WHERE project_id = :pid AND finding_id = :fid
                """)
                .param("pid", pid)
                .param("fid", findingId)
                .query(String.class)
                .list();
    }
}
