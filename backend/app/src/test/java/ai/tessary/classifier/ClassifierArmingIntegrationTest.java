// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.cases.CaseRow;
import ai.tessary.cases.CaseService;
import ai.tessary.classifier.finding.CauseKey;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.finding.FindingTitle;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakDetail;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakKeyView;
import ai.tessary.classifier.secretleak.SecretLeakEvidence.SecretLeakLeakView;
import ai.tessary.classifier.worker.ClassifierArming;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Classifier arming, which replaced the threshold {@code alert_rule} path.
 *
 * <p>Classifier-wide: N detections in the window open exactly one finding with the spans as members, a re-sweep
 * refreshes it, and an unarmed classifier files nothing. Faceted (Secret Leak): one finding per call site and
 * pattern, counted on the window each span happened in.
 */
@SpringBootTest
class ClassifierArmingIntegrationTest {

    private static final long DAY = 86_400;

    @Autowired
    ClassifierArming arming;

    @Autowired
    ClassifierDetectionWriteRepository detections;

    @Autowired
    FindingRepository findings;

    @Autowired
    TenantService tenants;

    @Autowired
    CaseService cases;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void fixtures() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    // ---- the classifier-wide shape ----------------------------------------------------------------

    @Test
    void armedWindowOpensOneFindingWithItsSpansAsEvidence_andReSweepingRefreshesIt() {
        String pid = TenantFixture.bootstrap(tenants, "arming-open").project().id();
        // Exactly at the bar: decides whether "N" means "N" or "more than N".
        String classifierId = armedClassifier(pid, "regex", "event_count", 3, 86_400);

        List<FindingEvidenceRepository.Ref> refs = writeDetections(pid, classifierId, "regex", 3);
        List<String> filed = arming.evaluate(row(pid, classifierId, "regex"), pid, refs, Instant.now());

        assertEquals(1, filed.size(), "three detections against a bar of three arms the classifier");
        String findingId = filed.get(0);
        FindingRow finding = findings.findById(pid, findingId).orElseThrow();
        assertEquals("regex", finding.classifierKey());
        assertEquals(FindingRow.SubjectKind.CLASSIFIER, finding.subjectKind());
        assertEquals(classifierId, finding.subjectId(), "the subject of a per-span finding is the classifier");
        assertEquals(3, finding.sampleCount());
        assertEquals(3, evidenceCount(findingId, FindingEvidenceRow.Role.MEMBER), "the spans that fired are members");

        // A refresh, not a second finding: the count is assigned from the window, not accumulated.
        List<String> again = arming.evaluate(row(pid, classifierId, "regex"), pid, refs, Instant.now());
        assertEquals(List.of(findingId), again, "re-sweeping the same window refreshes the one finding");
        assertEquals(1, liveFindings(pid), "and opens no second one");
        assertEquals(
                3,
                findings.findById(pid, findingId).orElseThrow().sampleCount(),
                "the count is what the window holds, not how often the sweep ran");
        assertEquals(
                3,
                evidenceCount(findingId, FindingEvidenceRow.Role.MEMBER),
                "and the evidence set does not grow on a repeat reference");
    }

    @Test
    void anUnarmedClassifierNeverFilesAnything() {
        String pid = TenantFixture.bootstrap(tenants, "arming-absent").project().id();
        // No arming block, as a user's classifier ships: nothing is filed.
        String classifierId = classifier(pid, "regex", null);

        List<FindingEvidenceRepository.Ref> refs = writeDetections(pid, classifierId, "regex", 25);

        assertTrue(arming.evaluate(row(pid, classifierId, "regex"), pid, refs, Instant.now())
                .isEmpty());
        assertEquals(0, liveFindings(pid), "twenty-five detections and no configured bar is still no finding");
    }

    @Test
    void distinctUsersCountsSessionsNotDetections() {
        String pid = TenantFixture.bootstrap(tenants, "arming-users").project().id();
        String classifierId = armedClassifier(pid, "regex", "distinct_users", 3, 86_400);

        // The session basis counts people, not events.
        String sessionA = SubstrateV2Fixtures.sessionId();
        String sessionB = SubstrateV2Fixtures.sessionId();
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        refs.add(writeOne(pid, classifierId, "regex", sessionA));
        refs.add(writeOne(pid, classifierId, "regex", sessionA));
        refs.add(writeOne(pid, classifierId, "regex", sessionB));

        assertTrue(
                arming.evaluate(row(pid, classifierId, "regex"), pid, refs, Instant.now())
                        .isEmpty(),
                "two sessions is under a bar of three, however many detections they produced");
        String table = detections.tableFor("regex");
        assertTrue(
                jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE project_id = :pid AND classifier_id = :cid")
                                .param("pid", pid)
                                .param("cid", classifierId)
                                .query(Long.class)
                                .single()
                        >= 3,
                "the detections themselves are still there, whatever the arming verdict");
    }

    @Test
    void aBackfillOfOldSpansNeverArmsTodaysWindow() {
        String pid = TenantFixture.bootstrap(tenants, "arming-backfill-under")
                .project()
                .id();
        String classifierId = armedClassifier(pid, "regex", "event_count", 3, (int) DAY);

        // One match per event-time window, 30 to 90 days back. Summing them into today's window would wrongly arm.
        List<FindingEvidenceRepository.Ref> refs = List.of(
                writeOneAt(pid, classifierId, "regex", SubstrateV2Fixtures.sessionId(), daysAgo(30)),
                writeOneAt(pid, classifierId, "regex", SubstrateV2Fixtures.sessionId(), daysAgo(60)),
                writeOneAt(pid, classifierId, "regex", SubstrateV2Fixtures.sessionId(), daysAgo(90)));

        assertTrue(
                arming.evaluate(row(pid, classifierId, "regex"), pid, refs, Instant.now())
                        .isEmpty(),
                "one detection per day across three separate days crosses no single window's bar");
        assertEquals(0, liveFindings(pid));
    }

    @Test
    void aBackfilledDayThatCrossesTheBarFilesUnderItsOwnDay() {
        String pid = TenantFixture.bootstrap(tenants, "arming-backfill-over")
                .project()
                .id();
        String classifierId = armedClassifier(pid, "regex", "event_count", 3, (int) DAY);
        Instant backfillDay = daysAgo(45);

        // One backfilled day crosses the bar and must file under that day, not today.
        List<FindingEvidenceRepository.Ref> refs = List.of(
                writeOneAt(pid, classifierId, "regex", SubstrateV2Fixtures.sessionId(), backfillDay),
                writeOneAt(pid, classifierId, "regex", SubstrateV2Fixtures.sessionId(), backfillDay.plusSeconds(60)),
                writeOneAt(pid, classifierId, "regex", SubstrateV2Fixtures.sessionId(), backfillDay.plusSeconds(120)));

        List<String> filed = arming.evaluate(row(pid, classifierId, "regex"), pid, refs, Instant.now());

        assertEquals(1, filed.size(), "the backfilled day crosses the bar");
        FindingRow finding = findings.findById(pid, filed.get(0)).orElseThrow();
        assertEquals(windowStart(backfillDay).toString(), finding.onsetAt(), "filed under the day the spans ran");
        assertEquals(3, finding.sampleCount());
    }

    // ---- the faceted shape (Secret Leak) ----------------------------------------------------------

    @Test
    void secretLeakFilesOneFindingPerCallSiteAndPattern_withTheLeakingSpansAsWitnesses() {
        String pid = TenantFixture.bootstrap(tenants, "arming-facets").project().id();
        String classifierId = armedSecretLeak(pid);
        Instant at = hoursAgo(1);

        List<FindingEvidenceRepository.Ref> refs = List.of(
                leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", at),
                leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", at.plusSeconds(1)),
                leak(pid, classifierId, "cs-a", "github-token", "high", at),
                leak(pid, classifierId, "cs-b", "aws-access-key-id", "high", at));

        List<String> filed = arming.evaluate(secretLeakRow(pid, classifierId), pid, refs, Instant.now());

        assertEquals(3, filed.size(), "two call sites and two patterns make three distinct causes");
        FindingRow awsA = facetFinding(pid, classifierId, "cs-a", "aws-access-key-id");
        FindingRow githubA = facetFinding(pid, classifierId, "cs-a", "github-token");
        FindingRow awsB = facetFinding(pid, classifierId, "cs-b", "aws-access-key-id");
        assertEquals(2, awsA.sampleCount(), "both AWS keys from cs-a are one cause");
        assertEquals(1, githubA.sampleCount());
        assertEquals(1, awsB.sampleCount(), "the same pattern at another call site is its own cause");
        assertEquals("cs-a", awsA.callSiteId());
        assertEquals(FindingRow.Cause.ARMED_WINDOW, awsA.causeKind());
        assertEquals("aws-access-key-id in cs-a output", FindingTitle.of(awsA));

        assertEquals(2, evidenceCount(awsA.id(), FindingEvidenceRow.Role.WITNESS), "the leaking spans are witnesses");
        assertEquals(
                0, evidenceCount(awsA.id(), FindingEvidenceRow.Role.MEMBER), "and a witness set claims no enumeration");

        List<String> again = arming.evaluate(secretLeakRow(pid, classifierId), pid, refs, Instant.now());
        assertTrue(again.isEmpty(), "all three are high confidence and so already ruled; a re-sweep files nothing");
        assertEquals(3, liveFindings(pid), "and opens none beside them");
        assertEquals(2, evidenceCount(awsA.id(), FindingEvidenceRow.Role.WITNESS), "without re-adding witnesses");
    }

    @Test
    void aLeakUploadedLateFilesUnderTheDayItHappened_notTheDayItWasChecked() {
        String pid = TenantFixture.bootstrap(tenants, "arming-late").project().id();
        String classifierId = armedSecretLeak(pid);
        // A backfill. Bucketing on sweep time would file it as today's leak, or not at all at a bar of one.
        Instant happened = daysAgo(40);

        List<String> filed = arming.evaluate(
                secretLeakRow(pid, classifierId),
                pid,
                List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", happened)),
                Instant.now());

        assertEquals(1, filed.size(), "a leak discovered late is still a finding");
        FindingRow finding = findings.findById(pid, filed.get(0)).orElseThrow();
        assertEquals(windowStart(happened).toString(), finding.onsetAt(), "the spell starts on the day it happened");
        assertEquals(happened, Instant.parse(finding.lastSeenAt()), "and was last seen when it happened");
    }

    /**
     * bf70c1d: a leak found long after it happened opened no case, because the case source kept only findings seen in
     * the past 48 hours. The key stays exposed until rotated.
     */
    @Test
    void aHighConfidenceLeakFoundFortyDaysLateStillOpensItsCase() {
        String pid =
                TenantFixture.bootstrap(tenants, "arming-late-case").project().id();
        String classifierId = armedSecretLeak(pid);

        String findingId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", daysAgo(40))),
                        Instant.now())
                .get(0);

        String caseId = findings.findById(pid, findingId).orElseThrow().caseId();
        assertNotNull(caseId, "a leak discovered late still opens a case");
        assertEquals(
                CaseRow.State.OPEN,
                jdbc.sql("SELECT state FROM eval_case WHERE id = :id")
                        .param("id", caseId)
                        .query(String.class)
                        .single(),
                "and the case stays open until a person resolves it");
    }

    /**
     * bf70c1d: high confidence is sticky. A late older high window after a newer low one must make the finding high.
     */
    @Test
    void anOlderHighWindowArrivingAfterANewerLowOneMakesTheFindingHighAndOpensItsCase() {
        String pid = TenantFixture.bootstrap(tenants, "arming-sticky-late-high")
                .project()
                .id();
        String classifierId = armedSecretLeakAnyBand(pid);

        String findingId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "low", hoursAgo(1))),
                        Instant.now())
                .get(0);
        assertNull(findings.findById(pid, findingId).orElseThrow().triageVerdict(), "low alone is left to triage");

        List<String> late = arming.evaluate(
                secretLeakRow(pid, classifierId),
                pid,
                List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", daysAgo(3))),
                Instant.now());

        assertEquals(List.of(findingId), late, "the older window refreshes the same finding");
        FindingRow after = findings.findById(pid, findingId).orElseThrow();
        assertEquals(
                FindingRow.Confidence.HIGH, after.payload().path("confidence").asText(), "one high window is enough");
        assertEquals(FindingRow.TriageVerdict.POSITIVE, after.triageVerdict(), "so it is ruled at arming");
        assertNotNull(after.caseId(), "and its case opens");
    }

    /**
     * bf70c1d, the other order: a newer low window must not downgrade a high finding. Written straight to the
     * repository, since arming rules a high finding at once and a ruled row is never refreshed.
     */
    @Test
    void aNewerLowWindowRefreshingAHighFindingKeepsItHigh() {
        String pid = TenantFixture.bootstrap(tenants, "arming-sticky-newer-low")
                .project()
                .id();
        String classifierId = armedSecretLeakAnyBand(pid);
        String olderDay = windowStart(daysAgo(3)).toString();
        String newerDay = windowStart(daysAgo(1)).toString();

        FindingRepository.Recorded high = findings.recordArmedFacet(
                Ids.ulid(),
                pid,
                "secret_leak",
                classifierId,
                "secret_leak",
                "cs-a",
                "aws-access-key-id",
                1,
                olderDay,
                olderDay,
                facetPayload("high"),
                olderDay,
                Instant.now().toString());
        assertNotNull(high);
        FindingRepository.Recorded low = findings.recordArmedFacet(
                Ids.ulid(),
                pid,
                "secret_leak",
                classifierId,
                "secret_leak",
                "cs-a",
                "aws-access-key-id",
                1,
                newerDay,
                newerDay,
                facetPayload("low"),
                olderDay,
                Instant.now().toString());

        assertNotNull(low);
        assertEquals(high.findingId(), low.findingId(), "the newer window refreshes the unruled finding");
        FindingRow after = findings.findById(pid, high.findingId()).orElseThrow();
        assertEquals(newerDay, after.lastSeenAt(), "the newer window's fields replace the older one's");
        assertEquals(
                FindingRow.Confidence.HIGH, after.payload().path("confidence").asText(), "but not its confidence");
    }

    @Test
    void onlyTheHighBandCountsTowardTheSecretLeakBar() {
        String pid = TenantFixture.bootstrap(tenants, "arming-band").project().id();
        String classifierId = armedSecretLeak(pid);
        Instant at = hoursAgo(1);

        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            refs.add(leak(pid, classifierId, "cs-a", "redacted-secret", "low", at.plusSeconds(i)));
        }
        assertTrue(
                arming.evaluate(secretLeakRow(pid, classifierId), pid, refs, Instant.now())
                        .isEmpty(),
                "three low-band markers against a bar of one high is nothing, however many there are");

        refs.add(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", at));
        List<String> filed = arming.evaluate(secretLeakRow(pid, classifierId), pid, refs, Instant.now());

        assertEquals(1, filed.size(), "one high-band leak is");
        FindingRow finding = findings.findById(pid, filed.get(0)).orElseThrow();
        assertEquals(1, finding.sampleCount(), "and the low band neither counts nor rides along");
        assertEquals(1, evidenceCount(finding.id(), FindingEvidenceRow.Role.WITNESS));
    }

    @Test
    void anOlderWindowArrivingLateNeverMovesTheFindingBackwards() {
        String pid = TenantFixture.bootstrap(tenants, "arming-order").project().id();
        // Low band: a high finding is ruled at once and never refreshed.
        String classifierId = armedSecretLeakAnyBand(pid);
        Instant recent = hoursAgo(1);
        Instant older = daysAgo(3);

        String findingId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "low", recent)),
                        Instant.now())
                .get(0);
        FindingRow before = findings.findById(pid, findingId).orElseThrow();

        List<String> late = arming.evaluate(
                secretLeakRow(pid, classifierId),
                pid,
                List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "low", older)),
                Instant.now());

        assertEquals(List.of(findingId), late, "an older leak of the same cause refreshes the same finding");
        FindingRow after = findings.findById(pid, findingId).orElseThrow();
        assertEquals(before.onsetAt(), after.onsetAt(), "the spell does not restart on an old window");
        assertEquals(before.lastSeenAt(), after.lastSeenAt(), "last seen does not move backwards");
        assertEquals(before.sampleCount(), after.sampleCount(), "the count stays the newest window's");
        assertEquals(2, evidenceCount(findingId, FindingEvidenceRow.Role.WITNESS), "but the old leak is a witness");
    }

    @Test
    void aNewerWindowStaysInTheSpellUntilTwoWindowsWentQuiet() {
        String pid = TenantFixture.bootstrap(tenants, "arming-spell").project().id();
        // Low band, as above.
        String classifierId = armedSecretLeakAnyBand(pid);
        Instant twoDaysAgo = daysAgo(2);
        Instant tenDaysAgo = daysAgo(10);
        Instant recent = hoursAgo(1);

        String continuing = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "low", twoDaysAgo)),
                        Instant.now())
                .get(0);
        arming.evaluate(
                secretLeakRow(pid, classifierId),
                pid,
                List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "low", recent)),
                Instant.now());
        assertEquals(
                windowStart(twoDaysAgo).toString(),
                findings.findById(pid, continuing).orElseThrow().onsetAt(),
                "one quiet day between leaks is the same spell");

        String restarted = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-b", "aws-access-key-id", "low", tenDaysAgo)),
                        Instant.now())
                .get(0);
        arming.evaluate(
                secretLeakRow(pid, classifierId),
                pid,
                List.of(leak(pid, classifierId, "cs-b", "aws-access-key-id", "low", recent)),
                Instant.now());
        assertEquals(
                windowStart(recent).toString(),
                findings.findById(pid, restarted).orElseThrow().onsetAt(),
                "a leak after days of quiet is a new spell");
    }

    @Test
    void aHighConfidenceLeakIsRuledPositiveAtArmingWithACase_aLowOneStaysUnruled() {
        String pid = TenantFixture.bootstrap(tenants, "arming-rule").project().id();
        String classifierId = armedSecretLeakAnyBand(pid);
        Instant at = hoursAgo(1);

        arming.evaluate(
                secretLeakRow(pid, classifierId),
                pid,
                List.of(
                        leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", at),
                        leak(pid, classifierId, "cs-b", "redacted-secret", "low", at)),
                Instant.now());

        FindingRow high = facetFinding(pid, classifierId, "cs-a", "aws-access-key-id");
        assertEquals(FindingRow.Status.OPEN, high.status(), "a positive ruling keeps the finding open");
        assertEquals(FindingRow.TriageVerdict.POSITIVE, high.triageVerdict(), "high confidence skips triage");
        assertEquals(FindingRow.TriageAction.OPENED_CASE, high.triageAction());
        assertEquals(ClassifierArming.HIGH_CONFIDENCE_LEAK_SUMMARY, high.triageSummary());
        assertNotNull(high.triagedAt());
        assertNull(high.humanVerdictAt(), "no person ruled it");
        assertNotNull(high.caseId(), "and its case opened in the same pass");

        FindingRow low = facetFinding(pid, classifierId, "cs-b", "redacted-secret");
        assertEquals(FindingRow.Status.OPEN, low.status());
        assertNull(low.triageVerdict(), "low confidence is left for triage to rule on");
        assertNull(low.caseId(), "and opens no case on its own");
    }

    @Test
    void aRuledLeakWindowReSweptFilesNothing_aNewerWindowFilesAFreshRuledFindingOnTheSameCase() {
        String pid = TenantFixture.bootstrap(tenants, "arming-ruled-resweep")
                .project()
                .id();
        String classifierId = armedSecretLeak(pid);
        Instant older = daysAgo(3);
        List<FindingEvidenceRepository.Ref> first =
                List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", older));

        String firstId = arming.evaluate(secretLeakRow(pid, classifierId), pid, first, Instant.now())
                .get(0);
        FindingRow ruled = findings.findById(pid, firstId).orElseThrow();
        assertEquals(FindingRow.TriageVerdict.POSITIVE, ruled.triageVerdict());

        assertTrue(
                arming.evaluate(secretLeakRow(pid, classifierId), pid, first, Instant.now())
                        .isEmpty(),
                "re-sweeping the window the ruling covers files nothing");
        assertEquals(1, findingsFor(pid), "and forks no duplicate of the ruled finding");

        String secondId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", hoursAgo(1))),
                        Instant.now())
                .get(0);
        assertTrue(!secondId.equals(firstId), "a newer window files a fresh finding beside the ruled one");
        assertEquals(2, findingsFor(pid));
        FindingRow fresh = findings.findById(pid, secondId).orElseThrow();
        assertEquals(FindingRow.TriageVerdict.POSITIVE, fresh.triageVerdict(), "ruled at arming in turn");
        assertEquals(ruled.caseId(), fresh.caseId(), "and joins the cause's unlocked case");
    }

    @Test
    void aLaterLeakInARuledWindowFilesNothing_onlyTheNextWindowFilesAFreshRuledFinding() {
        String pid = TenantFixture.bootstrap(tenants, "arming-ruled-same-window")
                .project()
                .id();
        String classifierId = armedSecretLeak(pid);
        Instant day = windowStart(daysAgo(3));

        String firstId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", day.plusSeconds(3_600))),
                        Instant.now())
                .get(0);
        FindingRow ruled = findings.findById(pid, firstId).orElseThrow();
        assertEquals(FindingRow.TriageVerdict.POSITIVE, ruled.triageVerdict());

        // Detected later the same day: the ruling already covers this window.
        assertTrue(
                arming.evaluate(
                                secretLeakRow(pid, classifierId),
                                pid,
                                List.of(leak(
                                        pid,
                                        classifierId,
                                        "cs-a",
                                        "aws-access-key-id",
                                        "high",
                                        day.plusSeconds(5 * 3_600))),
                                Instant.now())
                        .isEmpty(),
                "a later leak in the ruled window files nothing");
        assertEquals(1, findingsFor(pid), "and forks no second finding for the same day");

        String secondId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(
                                pid, classifierId, "cs-a", "aws-access-key-id", "high", day.plusSeconds(DAY + 3_600))),
                        Instant.now())
                .get(0);
        assertTrue(!secondId.equals(firstId), "the next day's window files a fresh finding");
        assertEquals(2, findingsFor(pid));
        FindingRow fresh = findings.findById(pid, secondId).orElseThrow();
        assertEquals(FindingRow.TriageVerdict.POSITIVE, fresh.triageVerdict(), "ruled at arming in turn");
        assertEquals(ruled.caseId(), fresh.caseId(), "and joins the cause's case");
        assertEquals(
                1L,
                jdbc.sql("SELECT COUNT(*) FROM eval_case_event WHERE case_id = :id AND kind = 'recurred'")
                        .param("id", ruled.caseId())
                        .query(Long.class)
                        .single(),
                "as a recurrence");
    }

    @Test
    void aSecretLeakCaseListsKeysFromEveryFinding() {
        String pid =
                TenantFixture.bootstrap(tenants, "arming-case-keys").project().id();
        String classifierId = armedSecretLeak(pid);
        Instant older = windowStart(daysAgo(5)).plusSeconds(3_600);
        Instant newer = windowStart(daysAgo(2)).plusSeconds(3_600);

        String firstId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", "AKIA…OLD1", older)),
                        Instant.now())
                .get(0);
        String secondId = arming.evaluate(
                        secretLeakRow(pid, classifierId),
                        pid,
                        List.of(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", "AKIA…NEW2", newer)),
                        Instant.now())
                .get(0);
        String caseId = findings.findById(pid, firstId).orElseThrow().caseId();
        assertNotNull(caseId);
        assertEquals(caseId, findings.findById(pid, secondId).orElseThrow().caseId(), "both findings on one case");

        SecretLeakDetail detail = cases.detail(pid, caseId).secretLeak();
        assertNotNull(detail);
        assertEquals(
                Set.of("AKIA…OLD1", "AKIA…NEW2"),
                detail.keys().stream().map(SecretLeakKeyView::masked).collect(Collectors.toSet()),
                "every finding's key is a key to rotate");
        assertEquals(older.toString(), detail.firstAt(), "it started when the oldest finding's leak happened");
        assertEquals(2, detail.leakCount());
        assertEquals(
                Set.of("AKIA…OLD1", "AKIA…NEW2"),
                detail.leaks().stream().map(SecretLeakLeakView::masked).collect(Collectors.toSet()),
                "and the witnesses are both findings'");
    }

    @Test
    void witnessesStopAtTheCap() {
        String pid = TenantFixture.bootstrap(tenants, "arming-cap").project().id();
        String classifierId = armedSecretLeak(pid);
        Instant at = hoursAgo(1);

        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            refs.add(leak(pid, classifierId, "cs-a", "aws-access-key-id", "high", at.plusMillis(i)));
        }
        String findingId = arming.evaluate(secretLeakRow(pid, classifierId), pid, refs, Instant.now())
                .get(0);

        assertEquals(55, findings.findById(pid, findingId).orElseThrow().sampleCount(), "the count is not capped");
        assertEquals(50, evidenceCount(findingId, FindingEvidenceRow.Role.WITNESS), "the witnesses are");
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** An upsert: bootstrapping already seeds the built-in catalog, and the key is unique per project. */
    private String classifier(String pid, String key, @Nullable String configJson) {
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

    /** Secret Leak armed as the catalog ships it: one high-band leak in a day. */
    private String armedSecretLeak(String pid) {
        return classifier(
                pid,
                "secret_leak",
                "{\"arming\":{\"basis\":\"event_count\",\"threshold\":1,\"window_seconds\":86400,"
                        + "\"confidence\":\"high\"}}");
    }

    /** Secret Leak armed to count both bands, so a low-confidence facet files a finding of its own. */
    private String armedSecretLeakAnyBand(String pid) {
        return classifier(
                pid,
                "secret_leak",
                "{\"arming\":{\"basis\":\"event_count\",\"threshold\":1,\"window_seconds\":86400,"
                        + "\"confidence\":\"any\"}}");
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

    private ClassifierRow secretLeakRow(String pid, String classifierId) {
        return row(pid, classifierId, "secret_leak");
    }

    private List<FindingEvidenceRepository.Ref> writeDetections(String pid, String classifierId, String key, int n) {
        List<FindingEvidenceRepository.Ref> refs = new ArrayList<>();
        for (int i = 0; i < n; i++) refs.add(writeOne(pid, classifierId, key, SubstrateV2Fixtures.sessionId()));
        return refs;
    }

    /** A real span carrying one detection; the window resolves off {@code subject_started_at}. */
    private FindingEvidenceRepository.Ref writeOne(String pid, String classifierId, String key, String sessionId) {
        return writeOneAt(pid, classifierId, key, sessionId, Instant.now());
    }

    /** As {@link #writeOne}, with the span's own event time under test control. */
    private FindingEvidenceRepository.Ref writeOneAt(
            String pid, String classifierId, String key, String sessionId, Instant at) {
        SubstrateV2Fixtures.SpanRef span =
                fx.spanSeed(pid).sessionId(sessionId).at(at).writeRef();
        detections.insert(
                Ids.ulid(),
                key,
                pid,
                classifierId,
                key,
                null,
                sessionId,
                span.traceId(),
                span.spanId(),
                "warn",
                "high",
                null);
        return FindingEvidenceRepository.Ref.span(span.traceId(), span.spanId());
    }

    /**
     * A real span at {@code callSiteId} carrying a Secret Leak detection of {@code pattern}. Faceted arming reads the
     * call site and event time off the span.
     */
    private FindingEvidenceRepository.Ref leak(
            String pid, String classifierId, String callSiteId, String pattern, String confidence, Instant startedAt) {
        return leak(pid, classifierId, callSiteId, pattern, confidence, "AKIA…ZAM2", startedAt);
    }

    /** As {@link #leak}, leaking the key masked as {@code masked}. */
    private FindingEvidenceRepository.Ref leak(
            String pid,
            String classifierId,
            String callSiteId,
            String pattern,
            String confidence,
            String masked,
            Instant startedAt) {
        SubstrateV2Fixtures.SpanRef span =
                fx.spanSeed(pid).callSiteId(callSiteId).at(startedAt).writeRef();
        detections.insert(
                Ids.ulid(),
                "secret_leak",
                pid,
                classifierId,
                "secret_leak",
                null,
                null,
                span.traceId(),
                span.spanId(),
                "critical",
                confidence,
                "{\"pattern\":\"" + pattern + "\",\"source\":\"output\",\"masked\":\"" + masked
                        + "\",\"stored\":\"raw\"}");
        return FindingEvidenceRepository.Ref.span(span.traceId(), span.spanId());
    }

    /** The faceted arming payload for one {@code aws-access-key-id} window at {@code confidence}. */
    private static String facetPayload(String confidence) {
        return "{\"cause_kind\":\"armed_window\",\"native_cause_key\":\"aws-access-key-id\","
                + "\"facet\":\"aws-access-key-id\",\"call_site_id\":\"cs-a\",\"confidence\":\"" + confidence + "\"}";
    }

    private FindingRow facetFinding(String pid, String classifierId, String callSiteId, String pattern) {
        String id = jdbc.sql("SELECT id FROM finding WHERE project_id = :pid AND cause_key = :key")
                .param("pid", pid)
                .param("key", CauseKey.perSpanClassifierFacet(classifierId, callSiteId, pattern))
                .query(String.class)
                .single();
        return findings.findById(pid, id).orElseThrow();
    }

    /** Whole seconds: Postgres keeps microseconds and {@code Instant.now()} carries nanos. */
    private static Instant hoursAgo(long hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant daysAgo(long days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private static Instant windowStart(Instant at) {
        return Instant.ofEpochSecond(Math.floorDiv(at.getEpochSecond(), DAY) * DAY);
    }

    private long evidenceCount(String findingId, String role) {
        return jdbc.sql("SELECT COUNT(*) FROM finding_evidence WHERE finding_id = :id AND role = :role")
                .param("id", findingId)
                .param("role", role)
                .query(Long.class)
                .single();
    }

    private long findingsFor(String pid) {
        return jdbc.sql("SELECT COUNT(*) FROM finding WHERE project_id = :pid")
                .param("pid", pid)
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
