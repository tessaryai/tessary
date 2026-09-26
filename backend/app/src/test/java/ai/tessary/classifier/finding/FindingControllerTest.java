// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierService;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.groundedness.GroundednessEvidence;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorBaselineEventView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.frustration.FrustrationEvidence;
import ai.tessary.classifier.malformed.MalformedOutputEvidence;
import ai.tessary.classifier.metric.MetricBaselineRepository;
import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.classifier.metric.MetricBaselineRow.BucketKind;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.classifier.metric.MetricBaselineRow.State;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The findings API behind the Classifiers page. Every call resolves the path's project first, so the bugs are
 * a finding readable through another project's path, the confirmed filter dropped (every unruled claim
 * listed as if a ruling stood behind it) or stuck on, an evidence tab for another detector's cause answering
 * with rows instead of an empty page, and Layer-2 analysis pressable by someone who may not spend the org's
 * model.
 */
@SpringBootTest
class FindingControllerTest {

    @Autowired
    FindingController controller;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository evidence;

    @Autowired
    TenantService tenants;

    @Autowired
    OrgMembershipRepository memberships;

    @Autowired
    CapabilityFixture capabilities;

    @Autowired
    ClassifierService classifiers;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    MetricBaselineRepository baselines;

    @Autowired
    BehaviorBaselineEventRepository baselineEvents;

    /**
     * An unruled finding lists only when every finding is asked for; its pages read through the path's project, and
     * "real deviation" rules it positive.
     */
    @Test
    void aFindingIsReadAndRuledThroughItsOwnProjectsPathOnly() {
        var fix = TenantFixture.bootstrap(tenants, "finding-api-read");
        var other = TenantFixture.bootstrap(tenants, "finding-api-other");
        TenantContext owner = owner(fix);
        String org = fix.org().slug();
        String project = fix.project().slug();
        String id = finding(fix, BuiltInDetector.Kind.REGEX, "{\"cause_kind\":\"armed_window\"}");

        assertEquals(
                List.of(id),
                ids(requireNonNull(controller
                                .findings(owner, org, project, null, null, null, "all")
                                .data())
                        .findings()));
        assertEquals(
                List.of(),
                ids(requireNonNull(controller
                                .findings(owner, org, project, null, null, null, "confirmed")
                                .data())
                        .findings()));
        assertEquals(
                id,
                requireNonNull(controller.finding(owner, org, project, id).data())
                        .finding()
                        .id());
        assertEquals(
                1,
                requireNonNull(controller
                                .findingEvidence(owner, org, project, id, FindingEvidenceRow.Role.MEMBER, 10, "")
                                .data())
                        .rows()
                        .size());
        assertEquals(
                new MalformedOutputEvidence.FailingOutputPage(List.of(), 0, null),
                controller
                        .malformedOutputs(owner, org, project, id, "total", 10, "")
                        .data());
        assertEquals(
                new FrustrationEvidence.FrustratedSessionPage(List.of(), 0, null),
                controller
                        .frustratedSessions(owner, org, project, id, null, null, 50, "")
                        .data());
        assertEquals(
                new GroundednessEvidence.FlaggedAnswerPage(List.of(), 0, null),
                controller
                        .flaggedAnswers(owner, org, project, id, null, null, 50, "")
                        .data());
        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(
                                TessaryException.class,
                                () -> controller.finding(
                                        owner(other),
                                        other.org().slug(),
                                        other.project().slug(),
                                        id))
                        .error());

        BehaviorFindingView ruled = requireNonNull(controller
                .resolve(owner, org, project, id, new BehaviorResolutionRequest(BehaviorResolutionRequest.NOT_EXPECTED))
                .data());
        assertEquals(FindingRow.TriageVerdict.POSITIVE, ruled.triageVerdict());
    }

    /**
     * A frustration finding's sessions tab counts every cited conversation, or a named RCA cause's share; dropping
     * the cause would credit one cause with the whole population.
     */
    @Test
    void aFrustrationFindingsSessionsNarrowToTheNamedCause() {
        var fix = TenantFixture.bootstrap(tenants, "finding-api-frustration");
        String id = finding(
                fix,
                BuiltInDetector.Kind.FRUSTRATION,
                "{\"cause_kind\":\"frustration_rate\",\"native_cause_key\":\"checkout-agent\"}");
        evidence.record(
                fix.project().id(),
                id,
                FindingEvidenceRow.Role.WITNESS,
                List.of(FindingEvidenceRepository.Ref.trace("trace-frustrated")),
                Instant.now().toString());

        assertEquals(1, sessions(fix, id, null, null).total());
        assertEquals(1, sessions(fix, id, "rca-1", null).total(), "a report with no cause index names no cause");
        assertEquals(0, sessions(fix, id, "rca-1", 0).total(), "a cause no report holds cites no conversation");
    }

    private FrustrationEvidence.FrustratedSessionPage sessions(
            TenantFixture.Setup fix, String id, @Nullable String rcaReport, @Nullable Integer cause) {
        return requireNonNull(controller
                .frustratedSessions(owner(fix), fix.org().slug(), fix.project().slug(), id, rcaReport, cause, 50, "")
                .data());
    }

    /** A withheld classifier's finding is not found through its evidence tabs either. */
    @Test
    void aWithheldClassifiersFindingIsNotFoundThroughItsEvidenceTabs() {
        var fix = TenantFixture.bootstrap(tenants, "finding-api-withheld");
        String id = finding(fix, BuiltInDetector.Kind.TOOL_ERROR, "{\"cause_kind\":\"rate_shift\"}");
        capabilities.withhold(fix.org().id(), Capability.TOOL_ERROR);

        assertEquals(
                ClassifierError.FINDING_NOT_FOUND,
                assertThrows(
                                TessaryException.class,
                                () -> controller.malformedOutputs(
                                        owner(fix),
                                        fix.org().slug(),
                                        fix.project().slug(),
                                        id,
                                        "total",
                                        10,
                                        ""))
                        .error());
    }

    /**
     * Layer-2 analysis spends the org's own model, so a viewer, who may not manage the org, is refused and nothing is
     * queued; the owner's request queues the run.
     */
    @Test
    void onlyAManagerRequestsLayerTwoAnalysis() {
        var fix = TenantFixture.bootstrap(tenants, "finding-api-analyze");
        String org = fix.org().slug();
        String project = fix.project().slug();
        String id = finding(fix, BuiltInDetector.Kind.TOOL_ERROR, "{\"cause_kind\":\"rate_shift\"}");
        Principal member = tenants.upsertUserFromWorkos(
                "user_finding_member_" + System.nanoTime(),
                "finding-member+" + System.nanoTime() + "@example.com",
                "m",
                null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), member.id(), Role.VIEWER.wire(), Instant.now().toString()));
        TenantContext viewerSession = new TenantContext(member.id(), member.email(), null, null, null, null);

        ResponseStatusException refused = assertThrows(
                ResponseStatusException.class, () -> controller.analyze(viewerSession, org, project, id, null));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
        assertEquals(
                null, findings.findById(fix.project().id(), id).orElseThrow().escalatedAt());

        var queued = requireNonNull(
                controller.analyze(owner(fix), org, project, id, null).data());
        assertEquals(false, queued.alreadyEscalated());
        assertEquals(
                true, findings.findById(fix.project().id(), id).orElseThrow().escalatedAt() != null);
    }

    /** The project's own re-pins, newest first. */
    @Test
    void theBaselineChangelogListsTheProjectsRepins() {
        var fix = TenantFixture.bootstrap(tenants, "finding-api-changelog");
        String projectId = fix.project().id();
        classifiers.seedBuiltIns(projectId);
        String classifierId = ClassifierRows.byKey(signals, projectId, BuiltInDetector.Kind.DURATION_DRIFT)
                .orElseThrow()
                .id();
        String now = Instant.now().toString();
        String baselineId = baselines
                .ensure(new MetricBaselineRow(
                        Ids.ulid(),
                        projectId,
                        classifierId,
                        Measure.TURN_DURATION,
                        BucketKind.CALL_SITE,
                        "summarize",
                        State.ARMED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        now,
                        now))
                .id();
        String eventId = Ids.ulid();
        baselineEvents.insert(BehaviorBaselineEventRow.forBaseline(
                eventId,
                baselineId,
                projectId,
                BehaviorBaselineEventRow.Event.BASELINE_REPINNED,
                "turn_duration:summarize:slower:pinned",
                "2026-07-01T00:00:00Z",
                "{\"ratio\":2.4}"));

        assertEquals(
                List.of(new BehaviorBaselineEventView(
                        eventId,
                        BehaviorBaselineEventRow.Event.BASELINE_REPINNED,
                        FindingRow.GLOBAL_WORKFLOW,
                        "turn_duration:summarize:slower:pinned",
                        "2026-07-01T00:00:00Z",
                        "{\"ratio\": 2.4}")),
                controller
                        .baselineEvents(
                                owner(fix), fix.org().slug(), fix.project().slug(), 100)
                        .data());
    }

    private static TenantContext owner(TenantFixture.Setup fix) {
        return new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
    }

    private static List<String> ids(List<BehaviorFindingView> views) {
        return views.stream().map(BehaviorFindingView::id).toList();
    }

    /** One open, unruled finding with one span of evidence. */
    private String finding(TenantFixture.Setup fix, String classifierKey, String payload) {
        String now = Instant.now().toString();
        String id = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        fix.project().id(),
                        classifierKey,
                        "clf-" + classifierKey,
                        "cause-" + classifierKey,
                        1,
                        "checkout-agent",
                        payload,
                        now,
                        now,
                        now,
                        now))
                .findingId();
        evidence.record(
                fix.project().id(),
                id,
                FindingEvidenceRow.Role.MEMBER,
                List.of(FindingEvidenceRepository.Ref.span("trace-1", "span-1")),
                now);
        return id;
    }
}
