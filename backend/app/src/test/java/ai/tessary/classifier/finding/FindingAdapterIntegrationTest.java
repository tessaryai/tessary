// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.tessary.classifier.finding.BehaviorDtos.BehaviorAnalysisView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorResolutionRequest;
import ai.tessary.classifier.finding.TriageSource.Escalatable;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A {@link FindingAdapter} on the classpath, against the real schema and the real Spring wiring: a finding under the
 * key it owns is escalated, carries its {@code method.md} into triage, and calls its hook when a person absorbs it.
 *
 * <p>The adapter is a {@code @Bean} of a nested {@code @TestConfiguration}, never a {@code @Component}: a component
 * under {@code ai.tessary} in this module's tests would join every {@code @SpringBootTest} here, and
 * {@code ContextLoadsTest} and {@code RcaWorkerTest} would stop proving the open edition's no-adapter boot.
 */
@SpringBootTest
@Import(FindingAdapterIntegrationTest.AdapterConfig.class)
class FindingAdapterIntegrationTest {

    private static final String OWNED_KEY = "custom-x";
    private static final String METHOD = "# Method x\n";

    @TestConfiguration
    static class AdapterConfig {
        @Bean
        RecordingAdapter recordingAdapter() {
            return new RecordingAdapter();
        }
    }

    /**
     * Owns {@link #OWNED_KEY}, hands back {@link #METHOD}, and records the finding id of each absorb with the name of
     * the transaction it ran in.
     */
    static final class RecordingAdapter implements FindingAdapter {
        record Absorb(String findingId, @Nullable String transaction) {}

        private final List<Absorb> absorbed = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean owns(FindingClaim finding) {
            return OWNED_KEY.equals(finding.classifierKey());
        }

        @Override
        public Optional<String> method(FindingClaim finding) {
            return Optional.of(METHOD);
        }

        @Override
        public void absorbed(FindingRow finding, @Nullable String userId, String now) {
            String transaction = TransactionSynchronizationManager.isActualTransactionActive()
                    ? TransactionSynchronizationManager.getCurrentTransactionName()
                    : null;
            absorbed.add(new Absorb(finding.id(), transaction));
        }

        List<Absorb> absorbsOf(String findingId) {
            synchronized (absorbed) {
                return absorbed.stream()
                        .filter(a -> a.findingId().equals(findingId))
                        .toList();
            }
        }
    }

    @Autowired
    BehaviorTriageSource source;

    @Autowired
    FindingService findingService;

    @Autowired
    FindingRepository findings;

    @Autowired
    FindingEvidenceRepository findingEvidence;

    @Autowired
    TenantService tenants;

    @Autowired
    RecordingAdapter adapter;

    /** The candidate read (one row per key, keys outside the built-in list) and the shared eligibility predicate
     *  admit the owned key's finding and not an un-owned custom key's. */
    @Test
    @DisplayName("an owned finding is automatically escalatable and an un-owned custom finding is not")
    void anOwnedFindingIsAutoEscalatableAndAnUnownedOneIsNot() {
        String pid = project("fa-escalate");
        String owned = finding(pid, OWNED_KEY, "clf-owned");
        String unowned = finding(pid, "user-regex-1", "clf-unowned");

        List<String> ids = source.listAutoEscalatable(pid, 30, 50).stream()
                .map(Escalatable::findingId)
                .toList();

        assertEquals(List.of(owned), ids, "only the owned finding escalates, not the un-owned " + unowned);
    }

    /**
     * The candidate read against Postgres: one claim per key (the lowest id), only findings the escalation predicate
     * admits, never a key the caller excludes, and only the project's own. The bugs are a read that returns every row
     * of a key, one that skips the sample or evidence rule, and one that reads another project's findings.
     */
    @Test
    @DisplayName("the candidate read gives one eligible claim per key, of the project's own findings")
    void theCandidateReadGivesOneEligibleClaimPerKeyOfTheProject() {
        String pid = project("fa-candidates");
        String other = project("fa-candidates-other");
        String a = finding(pid, OWNED_KEY, "clf-a");
        String b = finding(pid, OWNED_KEY, "clf-b");
        finding(pid, "custom-few", "clf-few", 10, true);
        finding(pid, "custom-bare", "clf-bare", 40, false);
        finding(pid, "tool_error", "clf-tool");
        finding(other, "custom-other", "clf-other");

        List<String> claims = findings.escalationCandidatesByKey(pid, List.of("tool_error"), 30).stream()
                .map(c -> c.classifierKey() + " " + c.id())
                .toList();

        assertEquals(List.of(OWNED_KEY + " " + (a.compareTo(b) < 0 ? a : b)), claims);
    }

    /** Escalation and the triage dossier agree on ownership: the press queues a job, and the brief carries the
     *  adapter's method. */
    @Test
    @DisplayName("analyzing an owned finding queues triage, and its dossier holds the adapter's method.md")
    void analyzingAnOwnedFindingQueuesTriageWithItsMethod() {
        String pid = project("fa-analyze");
        String id = finding(pid, OWNED_KEY, "clf-owned");

        BehaviorAnalysisView view = findingService.analyze(pid, id, null);

        assertNotNull(view.jobId());
        assertNotNull(findings.findById(pid, id).orElseThrow().escalatedAt(), "the escalate-once marker is set");
        BehaviorTriageJobRow job =
                new BehaviorTriageJobRow(view.jobId(), pid, id, "claimed", null, null, 0, null, now(), now());
        TriageBrief brief = source.brief(job).orElseThrow();
        assertEquals(METHOD, brief.dossier().get("method.md"));
    }

    /** A person's absorb on an owned finding calls the hook once, inside the ruling's transaction, and closes the
     *  finding negative. The ruling's transaction is the one {@code FindingService.resolve} opens; the bug is a hook
     *  that runs outside it, so a ruling commits while the detector state it absorbs does not. */
    @Test
    @DisplayName("absorbing an owned finding calls the adapter's hook once, in the ruling's transaction, and closes it")
    void absorbingAnOwnedFindingCallsTheHookOnceAndClosesIt() {
        String pid = project("fa-absorb");
        String id = finding(pid, OWNED_KEY, "clf-owned");

        findingService.resolve(pid, id, BehaviorResolutionRequest.EXPECTED, "u1");

        assertEquals(
                List.of(new RecordingAdapter.Absorb(id, FindingService.class.getName() + ".resolve")),
                adapter.absorbsOf(id));
        FindingRow row = findings.findById(pid, id).orElseThrow();
        assertEquals(FindingRow.TriageVerdict.NEGATIVE, row.triageVerdict());
        assertNotNull(row.humanVerdictAt());
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    /** A per-span classifier's armed window over 40 samples, citing one witness trace, as a custom sweep files it. */
    private String finding(String projectId, String classifierKey, String classifierId) {
        return finding(projectId, classifierKey, classifierId, 40, true);
    }

    private String finding(
            String projectId, String classifierKey, String classifierId, long samples, boolean citesATrace) {
        String id = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        projectId,
                        classifierKey,
                        classifierId,
                        "Custom " + classifierKey,
                        samples,
                        "cs-1",
                        "{}",
                        now(),
                        now(),
                        now(),
                        now()))
                .findingId();
        if (!citesATrace) return id;
        findingEvidence.record(
                projectId,
                id,
                FindingEvidenceRow.Role.WITNESS,
                List.of(FindingEvidenceRepository.Ref.span("t-custom", "s-custom")),
                now());
        return id;
    }

    private static String now() {
        return Instant.now().toString();
    }
}
