// SPDX-License-Identifier: Apache-2.0
package ai.tessary.usage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.tessary.pricing.ModelRate;
import ai.tessary.pricing.ModelResolver;
import ai.tessary.pricing.PriceBookRepository;
import ai.tessary.storage.Timestamps;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    @Test
    void aSandboxRunWithNoReportedCostIsPricedFromTheBookThatNamesIt() {
        String pid = project();
        ModelRate rate = books.rateFor(models.resolve(PRICED_MODEL).orElseThrow())
                .orElseThrow(() -> new AssertionError("precondition: the boot-imported book prices " + PRICED_MODEL));

        accountant.recordSandboxRun(
                pid,
                "rca",
                PRICED_MODEL,
                PRICED_MODEL,
                1_000_000L,
                0L,
                2_000_000L,
                0L,
                null,
                new LlmUsageAccountant.Subject("behavior_finding", "finding-1"));

        LlmCallRow row = only(pid);
        // One million input tokens cost the per-MTok input rate, two million cache reads twice the cache-read
        // rate; the zero-count buckets are absent, not free, so they add nothing.
        BigDecimal expected = perMtok(rate.rates().inputPerMtok())
                .add(perMtok(rate.rates().cacheReadPerMtok()).multiply(BigDecimal.TWO))
                .setScale(10, RoundingMode.UNNECESSARY);
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
                        expected,
                        rate.priceBookVersion(),
                        null,
                        "behavior_finding",
                        "finding-1",
                        row.createdAt()),
                row);
    }

    @Test
    void aReportedCostIsKeptVerbatimAndNamesNoBook() {
        String sandbox = project();
        accountant.recordSandboxRun(
                sandbox, "triage", PRICED_MODEL, PRICED_MODEL, 10L, 20L, 0L, 0L, new BigDecimal("0.5"), null);
        LlmCallRow run = only(sandbox);
        assertEquals(new BigDecimal("0.5000000000"), run.costUsd());
        assertEquals(null, run.priceBookVersion(), "no book produced a harness-reported figure");
        assertEquals(null, run.subjectKind());

        String decisions = project();
        accountant.recordDecisionCall(
                decisions, "decision", "jev-latest", 10, 20, new BigDecimal("0.0123"), "book-v1", 250);
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

    @Test
    void aRunTheLedgerCannotRecordIsDroppedWithoutFailingTheCaller() {
        String lane = "lane-" + UUID.randomUUID();
        accountant.recordDecisionCall(null, lane, "jev-latest", 1, 1, null, null, 1);
        accountant.recordDecisionCall("  ", lane, "jev-latest", 1, 1, null, null, 1);
        assertEquals(
                0,
                jdbc.sql("SELECT count(*) FROM llm_call WHERE lane = :lane")
                        .param("lane", lane)
                        .query(Integer.class)
                        .single(),
                "a call with no project has nothing to hang off");

        // A count past the integer column is clamped to its ceiling rather than wrapped negative...
        String clamped = project();
        accountant.recordSandboxRun(
                clamped, "rca", "unpriced-model", "unpriced-model", Long.MAX_VALUE, 0L, 0L, 0L, null, null);
        LlmCallRow row = only(clamped);
        assertEquals(Integer.MAX_VALUE, row.inputTokens());
        assertEquals(null, row.costUsd(), "a model no book carries is unpriced, not free");

        // ...and a row the ledger refuses (here: its project was deleted mid-run) is lost from the ledger, not
        // from the run that spent the money.
        String deleted = "proj-" + UUID.randomUUID();
        assertDoesNotThrow(() -> accountant.recordSandboxRun(
                deleted, "rca", "unpriced-model", "unpriced-model", 10L, 1L, 0L, 0L, null, null));
        assertEquals(List.of(), rows(deleted));
    }

    private static BigDecimal perMtok(@Nullable BigDecimal rate) {
        return rate == null ? BigDecimal.ZERO : rate;
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
