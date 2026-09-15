// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.tessary.classifier.finding.FindingRepository.Recorded;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The freeze [R1]: once a finding has a triage ruling, a later close must not re-point {@code
 * finding.payload} — the same gate {@code MetricDriftSweep} already applies to its evidence refs via
 * {@link Recorded#ruled}. One test per recorder that upserts a payload on conflict; {@code
 * recordFiring} (behaviour drift) needs none, since its payload is already first-write-wins and never
 * touched on conflict.
 *
 * <p>Every test rules with {@link FindingRow.TriageVerdict#NEGATIVE}, which is the arm the javadoc on
 * each recorder calls out: closing a finding is accepting the behaviour, and a cause that keeps firing
 * afterwards is what {@code recurrences_since_verdict} is for — not a re-pointed claim.
 */
@SpringBootTest
class FindingPayloadFreezeIntegrationTest {

    private static final String FAR_PAST = "2000-01-01T00:00:00Z";

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private void rule(String pid, String findingId) {
        int updated = findings.recordTriage(
                pid,
                findingId,
                FindingRow.TriageVerdict.NEGATIVE,
                "not real",
                null,
                Instant.now().toString());
        assertEquals(1, updated, "the ruling itself must land for the freeze to mean anything");
    }

    @Test
    void recordShift_freezesPayloadOnceRuled() {
        String pid = project("freeze-shift");
        String baselineId = Ids.ulid();
        String causeKey = "cause-a";

        Recorded first = findings.recordShift(
                Ids.ulid(),
                pid,
                "duration_drift",
                baselineId,
                causeKey,
                10,
                null,
                "cs-1",
                "{\"ratio\":1.5}",
                Instant.now().toString(),
                FAR_PAST,
                Instant.now().toString());
        String payloadBefore =
                findings.findById(pid, first.findingId()).orElseThrow().payloadJson();

        rule(pid, first.findingId());

        // The bucket shifted again after the ruling: a new close, a different ratio, the SAME cause.
        Recorded second = findings.recordShift(
                Ids.ulid(),
                pid,
                "duration_drift",
                baselineId,
                causeKey,
                5,
                null,
                "cs-1",
                "{\"ratio\":9.9}",
                Instant.now().toString(),
                FAR_PAST,
                Instant.now().toString());

        assertEquals(first.findingId(), second.findingId(), "the same cause refreshes the same live row");
        FindingRow after = findings.findById(pid, first.findingId()).orElseThrow();
        assertEquals(payloadBefore, after.payloadJson(), "a ruled finding's payload must not be re-pointed");
        assertNotEquals(payloadBefore, "{\"ratio\":9.9}", "sanity: the second close really did carry a new payload");
        assertEquals(15, after.sampleCount(), "sample_count keeps accumulating past the ruling");
    }

    @Test
    void recordRecomputedRate_freezesPayloadOnceRuled_whileCountsKeepAssigning() {
        String pid = project("freeze-recomputed");
        String causeKey = CauseKey.toolError("tool:search_docs");

        Recorded first = findings.recordRecomputedRate(
                Ids.ulid(),
                pid,
                "tool_error",
                causeKey,
                FindingRow.Cause.RATE_SHIFT,
                "tool:search_docs",
                FindingRow.SubjectKind.TOOL,
                "tool:search_docs",
                "search_docs",
                20,
                "cs-1",
                null,
                "{\"rate\":{\"cur\":0.1}}",
                FAR_PAST,
                Instant.now().toString());
        String payloadBefore =
                findings.findById(pid, first.findingId()).orElseThrow().payloadJson();

        rule(pid, first.findingId());

        // A recompute pass after the ruling: the rate is still what the hourly aggregate says NOW, and
        // that number keeps assigning — only the payload freezes.
        Recorded second = findings.recordRecomputedRate(
                Ids.ulid(),
                pid,
                "tool_error",
                causeKey,
                FindingRow.Cause.RATE_SHIFT,
                "tool:search_docs",
                FindingRow.SubjectKind.TOOL,
                "tool:search_docs",
                "search_docs",
                35,
                "cs-1",
                null,
                "{\"rate\":{\"cur\":0.4}}",
                FAR_PAST,
                Instant.now().toString());

        assertEquals(first.findingId(), second.findingId());
        FindingRow after = findings.findById(pid, first.findingId()).orElseThrow();
        assertEquals(payloadBefore, after.payloadJson(), "a ruled finding's payload must not be re-pointed");
        assertEquals(35, after.sampleCount(), "an ASSIGNING writer's counts still refresh past the ruling");
    }

    @Test
    void recordArmedWindow_freezesPayloadOnceRuled() {
        String pid = project("freeze-armed-window");
        String classifierId = Ids.ulid();

        Recorded first = findings.recordArmedWindow(
                Ids.ulid(),
                pid,
                "frustration",
                classifierId,
                "Frustration",
                4,
                null,
                "{\"cause_kind\":\"armed_window\",\"observed\":4}",
                FAR_PAST,
                Instant.now().toString());
        String payloadBefore =
                findings.findById(pid, first.findingId()).orElseThrow().payloadJson();

        rule(pid, first.findingId());

        Recorded second = findings.recordArmedWindow(
                Ids.ulid(),
                pid,
                "frustration",
                classifierId,
                "Frustration",
                9,
                null,
                "{\"cause_kind\":\"armed_window\",\"observed\":9}",
                FAR_PAST,
                Instant.now().toString());

        assertEquals(first.findingId(), second.findingId());
        FindingRow after = findings.findById(pid, first.findingId()).orElseThrow();
        assertEquals(payloadBefore, after.payloadJson(), "a ruled finding's payload must not be re-pointed");
        assertEquals(9, after.sampleCount(), "an ASSIGNING writer's counts still refresh past the ruling");
    }

    @Test
    void recordArmedFacet_freezesPayloadOnceRuled_whileLastSeenKeepsMoving() {
        String pid = project("freeze-armed-facet");
        String classifierId = Ids.ulid();
        Instant onset = Instant.parse("2026-06-01T00:00:00Z");
        Instant firstSeen = onset;
        Instant laterSeen = onset.plusSeconds(30);

        Recorded first = findings.recordArmedFacet(
                Ids.ulid(),
                pid,
                "secret_leak",
                classifierId,
                "Secret Leak",
                "cs-a",
                "aws-access-key-id",
                1,
                onset.toString(),
                firstSeen.toString(),
                "{\"cause_kind\":\"armed_window\",\"observed\":1}",
                FAR_PAST,
                Instant.now().toString());
        String payloadBefore =
                findings.findById(pid, first.findingId()).orElseThrow().payloadJson();

        rule(pid, first.findingId());

        // A newer leak in the same facet after the ruling: the cause recurring, not a fresh claim.
        Recorded second = findings.recordArmedFacet(
                Ids.ulid(),
                pid,
                "secret_leak",
                classifierId,
                "Secret Leak",
                "cs-a",
                "aws-access-key-id",
                2,
                onset.toString(),
                laterSeen.toString(),
                "{\"cause_kind\":\"armed_window\",\"observed\":2}",
                FAR_PAST,
                Instant.now().toString());

        assertEquals(first.findingId(), second.findingId());
        FindingRow after = findings.findById(pid, first.findingId()).orElseThrow();
        assertEquals(payloadBefore, after.payloadJson(), "a ruled facet's payload must not be re-pointed");
        assertEquals(laterSeen.toString(), after.lastSeenAt(), "but last_seen_at keeps moving forward");
        assertEquals(
                1,
                after.recurrencesSinceVerdict(),
                "and the cause recurring after a closing ruling still bumps the counter");
    }
}
