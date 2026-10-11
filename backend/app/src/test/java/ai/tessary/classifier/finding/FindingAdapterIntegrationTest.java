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

    /** Owns {@link #OWNED_KEY}, hands back {@link #METHOD}, and records the finding id of each absorb. */
    static final class RecordingAdapter implements FindingAdapter {
        private final List<String> absorbed = Collections.synchronizedList(new ArrayList<>());

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
            absorbed.add(finding.id());
        }

        long absorbCalls(String findingId) {
            synchronized (absorbed) {
                return absorbed.stream().filter(findingId::equals).count();
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
     *  finding negative. */
    @Test
    @DisplayName("absorbing an owned finding calls the adapter's hook once and closes it")
    void absorbingAnOwnedFindingCallsTheHookOnceAndClosesIt() {
        String pid = project("fa-absorb");
        String id = finding(pid, OWNED_KEY, "clf-owned");

        findingService.resolve(pid, id, BehaviorResolutionRequest.EXPECTED, "u1");

        assertEquals(1, adapter.absorbCalls(id));
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
        String id = Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        projectId,
                        classifierKey,
                        classifierId,
                        "Custom " + classifierKey,
                        40,
                        "cs-1",
                        "{}",
                        now(),
                        now(),
                        now(),
                        now()))
                .findingId();
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
