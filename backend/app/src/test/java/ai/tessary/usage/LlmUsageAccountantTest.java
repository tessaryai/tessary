// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.storage.Timestamps;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The platform's own LLM spend as it lands in the {@code llm_call} ledger: which cost a row carries, which
 * book it names, and that a row the ledger cannot take never fails the run that spent the money.
 */
@SpringBootTest
class LlmUsageAccountantTest {

    // The llm_call subject kind a triage run is booked under, as E2bTriageSandbox writes it.
    private static final String SUBJECT_KIND = "behavior_finding";

    private static final String PRICED_MODEL = "claude-haiku-4-5";

    @Autowired
    LlmUsageAccountant accountant;

    @Autowired
    ModelResolver models;

    @Autowired
    PriceBookRepository books;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    /**
     * A sandbox run is billed at the cost OpenCode reported, from models.dev rates. Pricing a run that reported none
     * from the book would put a second, different calculation on the same run.
     */
    @Test
    void aSandboxRunWithNoReportedCostStaysUnpricedEvenWhenTheBookCarriesItsModel() {
        String pid = project();
        assertTrue(
                books.rateFor(models.resolve(PRICED_MODEL).orElseThrow()).isPresent(),
                "precondition: the boot-imported book prices " + PRICED_MODEL);

        accountant.recordSandboxRun(
                pid,
                "rca",
                PRICED_MODEL,
                false,
                1_000_000L,
                0L,
                2_000_000L,
                0L,
                null,
                new LlmUsageAccountant.Subject(SUBJECT_KIND, "finding-1"));

        LlmCallRow row = only(pid);
        assertEquals(
                new LlmCallRow(
                        row.id(),
                        pid,
                        "rca",
                        PRICED_MODEL,
                        null,
                        LlmCallRow.CostFunding.BYO,
                        1_000_000,
                        null,
                        2_000_000,
                        null,
                        null,
                        null,
                        null,
                        SUBJECT_KIND,
                        "finding-1",
                        row.createdAt()),
                row);
    }

    @Test
    void aReportedCostIsKeptVerbatimAndNamesNoBook() {
        String sandbox = project();
        accountant.recordSandboxRun(
                sandbox, "triage", PRICED_MODEL, false, 10L, 20L, 0L, 0L, new BigDecimal("0.5"), null);
        LlmCallRow run = only(sandbox);
        assertEquals(new BigDecimal("0.5000000000"), run.costUsd());
        assertEquals(null, run.priceBookVersion(), "no book produced an OpenCode-reported figure");
        assertEquals(null, run.subjectKind());

        String decisions = project();
        accountant.recordDecisionCall(
                decisions, "decision", "jev-latest", false, 10, 20, new BigDecimal("0.0123"), "book-v1", 250);
        LlmCallRow call = only(decisions);
        assertEquals(
                new LlmCallRow(
                        call.id(),
                        decisions,
                        "decision",
                        "jev-latest",
                        null,
                        LlmCallRow.CostFunding.BYO,
                        10,
                        20,
                        null,
                        null,
                        new BigDecimal("0.0123000000"),
                        "book-v1",
                        250,
                        null,
                        null,
                        call.createdAt()),
                call);
    }

    /** A decision call on the deployment's own provider is what the credit debit reads; booked BYO it is free. */
    @Test
    void aPlatformFundedDecisionCallIsBookedAsPlatform() {
        String pid = project();

        accountant.recordDecisionCall(
                pid, "frustration", "typesafe/jev-latest", true, 10, 0, new BigDecimal("0.00000042"), "book-v1", 90);

        assertEquals(LlmCallRow.CostFunding.PLATFORM, only(pid).funding());
    }

    @Test
    void aRunTheLedgerCannotRecordIsDroppedWithoutFailingTheCaller() {
        String lane = "lane-" + UUID.randomUUID();
        accountant.recordDecisionCall(null, lane, "jev-latest", false, 1, 1, null, null, 1);
        accountant.recordDecisionCall("  ", lane, "jev-latest", false, 1, 1, null, null, 1);
        assertEquals(
                0,
                jdbc.sql("SELECT count(*) FROM llm_call WHERE lane = :lane")
                        .param("lane", lane)
                        .query(Integer.class)
                        .single(),
                "a call with no project has nothing to hang off");

        // A count past the integer column is clamped to its ceiling rather than wrapped negative...
        String clamped = project();
        accountant.recordSandboxRun(clamped, "rca", "unpriced-model", false, Long.MAX_VALUE, 0L, 0L, 0L, null, null);
        LlmCallRow row = only(clamped);
        assertEquals(Integer.MAX_VALUE, row.inputTokens());
        assertEquals(null, row.costUsd(), "a run that reported no cost is unpriced, not free");

        // ...and a row the ledger refuses (here: its project was deleted mid-run) is lost from the ledger, not
        // from the run that spent the money.
        String deleted = "proj-" + UUID.randomUUID();
        assertDoesNotThrow(() ->
                accountant.recordSandboxRun(deleted, "rca", "unpriced-model", false, 10L, 1L, 0L, 0L, null, null));
        assertEquals(List.of(), rows(deleted));
    }

    private String project() {
        return TenantFixture.bootstrap(tenants, "llm-usage").project().id();
    }

    private LlmCallRow only(String pid) {
        List<LlmCallRow> rows = rows(pid);
        assertEquals(1, rows.size(), "exactly one ledger row for the project");
        LlmCallRow row = rows.get(0);
        assertNotNull(row.createdAt());
        return row;
    }

    private List<LlmCallRow> rows(String pid) {
        return jdbc.sql("SELECT * FROM llm_call WHERE project_id = :pid")
                .param("pid", pid)
                .query((rs, n) -> new LlmCallRow(
                        rs.getString("id"),
                        rs.getString("project_id"),
                        rs.getString("lane"),
                        rs.getString("model"),
                        rs.getString("service_tier"),
                        rs.getString("funding"),
                        (Integer) rs.getObject("input_tokens"),
                        (Integer) rs.getObject("output_tokens"),
                        (Integer) rs.getObject("cache_read_tokens"),
                        (Integer) rs.getObject("cache_write_tokens"),
                        rs.getBigDecimal("cost_usd"),
                        rs.getString("price_book_version"),
                        (Integer) rs.getObject("latency_ms"),
                        rs.getString("subject_kind"),
                        rs.getString("subject_id"),
                        Timestamps.iso(rs, "created_at")))
                .list();
    }
}
