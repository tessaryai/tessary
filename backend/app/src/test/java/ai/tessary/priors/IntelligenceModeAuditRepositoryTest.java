// SPDX-License-Identifier: Apache-2.0
package ai.tessary.priors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.priors.IntelligenceModeAuditRepository.IntelligenceModeAudit;
import ai.tessary.tenant.Ids;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the SOC 2 evidence trail against the real Testcontainers Postgres (the schema
 * creates {@code intelligence_mode_audit}). The boot-time row is written by
 * {@link IntelligenceModeAuditor} on {@code ApplicationReadyEvent}; here we exercise the append-only
 * repository directly and confirm the auditor's boot row is present (single-tenant is the default).
 */
@SpringBootTest
// Own context on purpose: it asserts the auditor's boot-time evidence row, which is written once per context and
// nowhere else.
@TestPropertySource(properties = "test.context-group=intelligence-mode-audit")
class IntelligenceModeAuditRepositoryTest {

    @Autowired
    IntelligenceModeAuditRepository repo;

    @Test
    void bootEvidenceRow_isRecorded_withSingleTenantDefault() {
        List<IntelligenceModeAudit> rows = repo.recent(50);
        assertFalse(rows.isEmpty(), "the auditor must record a boot-time evidence row");
        // The default deployment shape is single-tenant; no test overrides the mode here.
        assertTrue(
                rows.stream().anyMatch(IntelligenceModeAudit::singleTenant),
                "boot evidence records single-tenant mode (the enterprise default)");
    }

    @Test
    void record_appendsImmutableRows_newestFirst() {
        // Unique-per-run timestamps so these two rows sort together at the very top of the table,
        // independent of any other rows (the auditor's boot row, or rows from a prior run).
        long base = System.currentTimeMillis();
        String tEarlier = Instant.ofEpochMilli(base).plusSeconds(3_000_000_000L).toString();
        String tLater = Instant.ofEpochMilli(base).plusSeconds(3_000_000_001L).toString();
        String idEarlier = Ids.ulid();
        String idLater = Ids.ulid();
        repo.record(idEarlier, true, false, tEarlier);
        repo.record(idLater, false, true, tLater);

        // Both rows are read back unchanged (append-only persistence), and DESC ordering places the
        // later-timestamped row ahead of the earlier one within our own pair.
        List<IntelligenceModeAudit> all = repo.recent(10_000);
        List<IntelligenceModeAudit> ours = all.stream()
                .filter(r -> r.id().equals(idEarlier) || r.id().equals(idLater))
                .toList();
        assertEquals(2, ours.size(), "both appended rows are present");

        IntelligenceModeAudit earlier =
                ours.stream().filter(r -> r.id().equals(idEarlier)).findFirst().orElseThrow();
        IntelligenceModeAudit later =
                ours.stream().filter(r -> r.id().equals(idLater)).findFirst().orElseThrow();

        // Values round-trip immutably.
        assertTrue(earlier.singleTenant());
        assertFalse(earlier.poolingEnabled());
        assertEquals(tEarlier, earlier.recordedAt());
        assertFalse(later.singleTenant());
        assertTrue(later.poolingEnabled());
        assertEquals(tLater, later.recordedAt());

        // recorded_at DESC: the later row sorts ahead of the earlier row in the returned list.
        assertTrue(
                ours.indexOf(later) < ours.indexOf(earlier),
                "newest-first ordering: later-timestamped row precedes the earlier one");
    }
}
