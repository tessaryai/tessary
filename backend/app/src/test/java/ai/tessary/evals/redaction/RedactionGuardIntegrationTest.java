// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.ingest.KindNormalizer;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.ingest.substrate.SubstrateWriter;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance test for the PII redaction write-path guard against the real pgvector Postgres
 * (Testcontainers): a project's enabled redaction rules are applied on the {@code SubstrateWriter} drain,
 * immediately before the write, so no unredacted PII ever reaches the persisted substrate. Exercises both the seeded built-in
 * rules and a custom rule, and confirms repository round-trips + built-in seeding.
 */
@SpringBootTest
class RedactionGuardIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    SubstrateWriter writer;

    @Autowired
    RedactionService redaction;

    @Autowired
    RedactionRuleRepository repo;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    /** The persisted span input — the thing the guard exists to keep clean. */
    private String persistedInput(String pid) {
        return jdbc.sql("SELECT input FROM span_payload WHERE project_id = :pid LIMIT 1")
                .param("pid", pid)
                .query(String.class)
                .single();
    }

    /** The persisted attribute bag, where a producer's own keys land verbatim. */
    private String persistedAttributes(String pid) {
        return jdbc.sql("SELECT attributes FROM span_payload WHERE project_id = :pid LIMIT 1")
                .param("pid", pid)
                .query(String.class)
                .single();
    }

    private static RawEntry span(String input, String output, Map<String, Object> meta) {
        return new RawEntry(
                "span-1",
                null,
                "agent",
                input,
                output,
                null,
                meta,
                null,
                "trace-1",
                Instant.parse("2026-01-01T00:00:00Z").toString(),
                KindNormalizer.AGENT);
    }

    @Test
    void builtInRulesRedactPiiBeforePersistence() throws InterruptedException {
        String pid =
                TenantFixture.bootstrap(tenants, "redact-builtin").project().id();
        // listRules seeds the built-in starter set (email/SSN/card/phone/ip) on first access.
        assertFalse(redaction.listRules(pid).isEmpty(), "built-in rules seeded");

        writer.enqueue(
                pid,
                List.of(span(
                        "email me at jane.doe@example.com or call 415-555-1234",
                        "ok",
                        Map.of("note", "user ssn is 123-45-6789"))));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        String input = persistedInput(pid);
        assertFalse(input.contains("jane.doe@example.com"), "email must be stripped before persistence");
        assertTrue(input.contains("[REDACTED_EMAIL]"), "email replaced with the rule's token");
        assertFalse(input.contains("415-555-1234"), "phone must be stripped");

        String metadata = persistedAttributes(pid);
        assertFalse(metadata.contains("123-45-6789"), "PII in metadata string values must be stripped too");
    }

    /**
     * A producer-reported cost is a quantity, and the redactor must leave it alone.
     *
     * <p>Found by the M3 live-traffic smoke, not by a unit test: a span carrying
     * {@code gen_ai.usage.cost = "0.0123456789"} had it rewritten to {@code [REDACTED_PHONE]} by the
     * built-in phone rule — redaction runs on the drain, immediately upstream of the write — after which
     * the v2 pricer could not parse it, silently fell through to inferring a price, and recorded
     * $0.000150 for a call the producer had said cost $0.0123456789. The same value one digit shorter
     * survived, so nothing about the failure was visible from the code.
     *
     * <p>The control half of this test is the point: PII in an ordinary attribute must still be stripped,
     * so the exemption is provably scoped to the usage/cost keys rather than a hole in the guard.
     */
    @Test
    void usageAndCostAttributesAreExemptFromRedaction() throws InterruptedException {
        String pid = TenantFixture.bootstrap(tenants, "redact-usage-exempt")
                .project()
                .id();
        assertFalse(redaction.listRules(pid).isEmpty(), "built-in rules seeded");

        writer.enqueue(
                pid,
                List.of(span(
                        "ok",
                        "ok",
                        Map.of(
                                "gen_ai.usage.cost", "0.0123456789",
                                "gen_ai.usage.input_tokens", "4155551234",
                                "note", "call me on 415-555-1234"))));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        String metadata = persistedAttributes(pid);
        assertTrue(metadata.contains("0.0123456789"), "a reported cost must reach the writer as a number");
        assertTrue(metadata.contains("4155551234"), "a reported token count must survive a phone-shaped value");
        assertFalse(metadata.contains("415-555-1234"), "an ordinary attribute is still redacted");
    }

    @Test
    void customRuleIsAppliedAndRoundTrips() throws InterruptedException {
        String pid = TenantFixture.bootstrap(tenants, "redact-custom").project().id();
        RedactionRuleRow rule = redaction.createRule(pid, "Account id", "ACC-\\d{6}", "[REDACTED_ACCT]", true, 5);

        // Repository round-trip: every column reads back.
        RedactionRuleRow back = repo.findById(pid, rule.id()).orElseThrow();
        assertEquals("Account id", back.name());
        assertEquals("ACC-\\d{6}", back.pattern());
        assertEquals("[REDACTED_ACCT]", back.replacement());
        assertTrue(back.enabled());
        assertFalse(back.builtIn());
        assertEquals(5, back.sortOrder());

        writer.enqueue(pid, List.of(span("your account ACC-123456 is active", "noted", Map.of())));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        String input = persistedInput(pid);
        assertFalse(input.contains("ACC-123456"), "custom-rule PII must be stripped");
        assertTrue(input.contains("[REDACTED_ACCT]"));
    }

    @Test
    void disabledRuleIsNotApplied() throws InterruptedException {
        String pid =
                TenantFixture.bootstrap(tenants, "redact-disabled").project().id();
        RedactionRuleRow rule = redaction.createRule(pid, "Token", "TKN-\\w+", "[X]", true, 5);
        redaction.setEnabled(pid, rule.id(), false);

        writer.enqueue(pid, List.of(span("secret TKN-abc123 here", "ok", Map.of())));
        assertTrue(writer.awaitIdle(Duration.ofSeconds(30)), "writer drained");

        // The disabled custom rule does not fire; built-ins (which don't match) leave this untouched.
        assertTrue(persistedInput(pid).contains("TKN-abc123"), "a disabled rule must not redact");
    }
}
