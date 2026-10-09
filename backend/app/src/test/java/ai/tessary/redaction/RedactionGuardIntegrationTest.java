// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.substrate.SubstrateWriter;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.redaction.RedactionDtos.PreviewRequest;
import ai.tessary.redaction.RedactionDtos.PreviewView;
import ai.tessary.redaction.RedactionDtos.RuleView;
import ai.tessary.redaction.RedactionDtos.SetEnabledRequest;
import ai.tessary.redaction.RedactionDtos.UpsertRuleRequest;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Acceptance test for the PII redaction write-path guard against the real pgvector Postgres
 * (Testcontainers): a project's enabled redaction rules are applied on the {@code SubstrateWriter} drain,
 * immediately before the write, so no unredacted PII ever reaches the persisted substrate. Exercises both the seeded built-in
 * rules and a custom rule, and confirms repository round-trips + built-in seeding.
 */
@SpringBootTest
class RedactionGuardIntegrationTest {

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

    @Autowired
    RedactionController controller;

    @Autowired
    CapabilityFixture capabilities;

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

    // ---- the playground endpoints --------------------------------------------------------------

    private static TenantContext session(TenantFixture.Setup fix) {
        return new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
    }

    /**
     * An owner authors a rule through the endpoints: create with the defaults, an edit whose every column
     * reads back from the table, a toggle, a preview of the unsaved rule and of the whole active set, then a
     * delete that removes the row.
     */
    @Test
    void playground_authorsACustomRuleEndToEnd() {
        TenantFixture.Setup fix = TenantFixture.bootstrap(tenants, "redact-playground");
        TenantContext owner = session(fix);
        String org = fix.org().slug();
        String proj = fix.project().slug();
        String pid = fix.project().id();

        RuleView created = controller
                .create(owner, org, proj, new UpsertRuleRequest("Ticket", "TKT-\\d+", "[TICKET]", null, null))
                .data();
        assertTrue(created.enabled(), "a rule is on unless the request says otherwise");
        assertEquals(100, created.sortOrder(), "an unordered rule runs after every built-in");

        RuleView edited = controller
                .update(
                        owner,
                        org,
                        proj,
                        created.id(),
                        new UpsertRuleRequest("Ticket id", "TKT-\\d{3}", "[T]", false, 7))
                .data();
        assertEquals(
                new RuleView(
                        created.id(),
                        "Ticket id",
                        "TKT-\\d{3}",
                        "[T]",
                        false,
                        false,
                        7,
                        created.createdAt(),
                        edited.updatedAt()),
                edited);
        assertEquals(edited, RuleView.of(repo.findById(pid, created.id()).orElseThrow()), "every column reads back");

        assertTrue(controller
                .setEnabled(owner, org, proj, created.id(), new SetEnabledRequest(true))
                .data()
                .enabled());
        assertTrue(repo.findById(pid, created.id()).orElseThrow().enabled());

        assertEquals(
                new PreviewView("TKT-123 open", " open", 1),
                controller
                        .preview(owner, org, proj, new PreviewRequest("TKT-\\d+", null, "TKT-123 open"))
                        .data(),
                "an unsaved rule with no replacement removes the match");
        assertEquals(
                new PreviewView("TKT-123 open", "[T] open", 1),
                controller
                        .preview(owner, org, proj, new PreviewRequest(" ", null, "TKT-123 open"))
                        .data(),
                "no pattern previews the project's active rule set");

        controller.delete(owner, org, proj, created.id());
        assertTrue(repo.findById(pid, created.id()).isEmpty());
        assertFalse(
                controller.list(owner, org, proj).data().rules().stream().anyMatch(r -> r.id().equals(created.id())));
    }

    /**
     * Without {@code custom_redaction_enabled} an org still reads its rules, switches a default off and
     * previews what it strips (a security review needs all three), but authoring a rule, including trying an
     * unsaved pattern, is refused.
     */
    @Test
    void playground_withoutCustomRedaction_readsTogglesAndPreviewsButCannotAuthor() {
        TenantFixture.Setup fix = TenantFixture.bootstrap(tenants, "redact-gated");
        capabilities.withhold(fix.org().id(), Capability.CUSTOM_REDACTION);
        TenantContext owner = session(fix);
        String org = fix.org().slug();
        String proj = fix.project().slug();

        RuleView builtIn = controller.list(owner, org, proj).data().rules().get(0);
        assertFalse(controller
                .setEnabled(owner, org, proj, builtIn.id(), new SetEnabledRequest(false))
                .data()
                .enabled());
        assertEquals(
                "mail [REDACTED_EMAIL]",
                controller
                        .preview(owner, org, proj, new PreviewRequest(null, null, "mail jane@example.com"))
                        .data()
                        .redacted());

        UpsertRuleRequest rule = new UpsertRuleRequest("Ticket", "TKT-\\d+", "[T]", true, 5);
        assertEquals(
                CapabilityError.DISABLED,
                assertThrows(TessaryException.class, () -> controller.create(owner, org, proj, rule))
                        .error());
        assertEquals(
                CapabilityError.DISABLED,
                assertThrows(TessaryException.class, () -> controller.update(owner, org, proj, builtIn.id(), rule))
                        .error());
        assertEquals(
                CapabilityError.DISABLED,
                assertThrows(TessaryException.class, () -> controller.delete(owner, org, proj, builtIn.id()))
                        .error());
        assertEquals(
                CapabilityError.DISABLED,
                assertThrows(
                                TessaryException.class,
                                () -> controller.preview(
                                        owner, org, proj, new PreviewRequest("TKT-\\d+", "[T]", "TKT-1")))
                        .error(),
                "trying an unsaved pattern is authoring");
        assertTrue(repo.findById(fix.project().id(), builtIn.id()).isPresent(), "the refused delete removed nothing");
    }
}
