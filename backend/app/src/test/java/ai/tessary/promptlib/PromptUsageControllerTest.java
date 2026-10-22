// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.promptlib.PromptUsageDtos.PromptCalls;
import ai.tessary.promptlib.PromptUsageDtos.PromptUsage;
import ai.tessary.promptlib.PromptUsageDtos.VersionUsage;
import ai.tessary.storage.SpanRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.web.ApiResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Prompt usage against real spans: one row per version string with its latency percentiles, tokens, cost and
 * unpriced count; only settled, live traces and live llm spans count; the window is {@code days} long and clamped; a
 * version string no prompt version knows is named; and a write-only key reads nothing.
 */
@SpringBootTest
record PromptUsageControllerTest(
        SubstrateV2Fixtures fx,
        PromptUsageRepository repository,
        TenantPathResolver resolver,
        TenantService tenants,
        OrgMembershipRepository memberships,
        JdbcClient jdbc) {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private record Tenant(TenantContext ctx, String org, String proj, String pid, String orgId) {}

    private PromptUsageController controller() {
        return new PromptUsageController(resolver, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void groupsByVersionWithPercentiles() {
        // Bug: the group is wrong, a percentile is a mean or off by one rank, or a sum counts the wrong spans.
        Tenant t = tenant("usage-percentiles");
        String id = prompt(t.pid(), "summarize", 1);
        Instant first = NOW.minus(Duration.ofHours(4));
        call(t.pid(), "summarize", "1", first, 100, false, true);
        call(t.pid(), "summarize", "1", NOW.minus(Duration.ofHours(3)), 200, true, true);
        call(t.pid(), "summarize", "1", NOW.minus(Duration.ofHours(2)), 300, false, false);
        Instant last = NOW.minus(Duration.ofHours(1));
        call(t.pid(), "summarize", "1", last, 400, false, false);

        PromptUsage usage = usage(t, id, null);

        assertEquals(
                new PromptUsage(
                        7,
                        List.of(new VersionUsage(
                                "1",
                                4,
                                1,
                                250L,
                                385L,
                                40L,
                                20L,
                                new BigDecimal("0.003000"),
                                2,
                                first.toString(),
                                last.toString())),
                        List.of()),
                usage,
                "p50 of 100..400 is 250; p95 is 300 + 0.85 x 100; two priced calls at 0.0015 each");
    }

    @Test
    void callsWithNoLatencyHaveNoPercentiles() {
        // Bug: a version whose spans reported no end time fails to unbox, or reads as a 0 ms percentile.
        Tenant t = tenant("usage-no-latency");
        String id = prompt(t.pid(), "summarize", 1);
        settle(fx.spanSeed(t.pid())
                .prompt("summarize", "1")
                .at(NOW.minus(Duration.ofHours(1)))
                .write());

        VersionUsage row = usage(t, id, null).versions().get(0);
        assertEquals(Arrays.asList(1L, null, null), Arrays.asList(row.calls(), row.p50Ms(), row.p95Ms()));
    }

    @Test
    void unsettledAndDeletedTracesDoNotCount() {
        // Bug: a trace still receiving spans counts before it settles, or a deleted trace keeps counting.
        Tenant t = tenant("usage-settled");
        String id = prompt(t.pid(), "summarize", 1);
        call(t.pid(), "summarize", "1", NOW.minus(Duration.ofHours(3)), 100, false, false);
        fx.spanSeed(t.pid())
                .prompt("summarize", "1")
                .at(NOW.minus(Duration.ofHours(2)))
                .write();
        SpanRow deleted = call(t.pid(), "summarize", "1", NOW.minus(Duration.ofHours(1)), 100, false, false);
        jdbc.sql("UPDATE trace SET is_deleted = true WHERE project_id = :pid AND id = :tid")
                .param("pid", t.pid())
                .param("tid", deleted.traceId())
                .update();

        assertEquals(1, usage(t, id, null).versions().get(0).calls());
    }

    @Test
    void nonLlmAndDeletedSpansDoNotCount() {
        // Bug: a tool or agent span that carries the attribute counts as a model call, or a deleted span counts.
        Tenant t = tenant("usage-llm-only");
        String id = prompt(t.pid(), "summarize", 1);
        SpanRow tool = fx.spanSeed(t.pid())
                .kind("tool")
                .prompt("summarize", "1")
                .at(NOW.minus(Duration.ofHours(2)))
                .write();
        settle(tool);
        SpanRow deleted = call(t.pid(), "summarize", "1", NOW.minus(Duration.ofHours(1)), 100, false, false);
        jdbc.sql("UPDATE span SET is_deleted = true WHERE project_id = :pid AND trace_id = :tid AND id = :sid")
                .param("pid", t.pid())
                .param("tid", deleted.traceId())
                .param("sid", deleted.id())
                .update();

        assertEquals(List.of(), usage(t, id, null).versions());
        assertEquals(List.of(), summary(t, null).items());
    }

    @Test
    void windowIsDaysLong() {
        // Bug: the window ignores days, or an out-of-range days is passed to SQL instead of clamped.
        Tenant t = tenant("usage-window");
        String id = prompt(t.pid(), "summarize", 1);
        call(t.pid(), "summarize", "1", NOW.minus(Duration.ofDays(8)), 100, false, false);

        assertEquals(List.of(), usage(t, id, 7).versions());
        assertEquals(1, usage(t, id, 30).versions().get(0).calls());
        assertEquals(7, usage(t, id, 0).days());
        assertEquals(90, usage(t, id, 400).days());
        assertEquals(7, summary(t, 0).days());
    }

    @Test
    void unknownVersionsAreTheVersionsNoRowKnows() {
        // Bug: a label or a deleted version disappears from the page, or a null version is reported as unknown.
        Tenant t = tenant("usage-unknown");
        String id = prompt(t.pid(), "summarize", 1, 2);
        Instant at = NOW.minus(Duration.ofHours(1));
        call(t.pid(), "summarize", "1", at, 100, false, false);
        call(t.pid(), "summarize", "7", at, 100, false, false);
        call(t.pid(), "summarize", "prod", at, 100, false, false);
        call(t.pid(), "summarize", null, at, 100, false, false);

        PromptUsage usage = usage(t, id, null);

        assertEquals(
                Arrays.asList("1", "7", "prod", null),
                usage.versions().stream().map(VersionUsage::version).toList());
        assertEquals(List.of("7", "prod"), usage.unknownVersions());
    }

    @Test
    void unknownPromptIs404() {
        // Bug: an unknown or another project's prompt id answers an empty page instead of a 404.
        Tenant t = tenant("usage-404");

        TessaryException ex = assertThrows(TessaryException.class, () -> usage(t, "no-such-prompt", null));
        assertEquals(PromptsError.NOT_FOUND, ex.error());
    }

    @Test
    void summaryListsEveryNameWithCalls() {
        // Bug: a name is missing, counted twice, out of order, or an unlinked span becomes a row.
        Tenant t = tenant("usage-summary");
        Instant at = NOW.minus(Duration.ofHours(1));
        call(t.pid(), "Q", "1", at, 100, false, false);
        call(t.pid(), "P", "1", at, 100, false, false);
        call(t.pid(), "P", "2", at, 100, false, false);
        call(t.pid(), "P", null, at, 100, false, false);
        settle(fx.spanSeed(t.pid()).at(at).write());

        assertEquals(
                List.of(new PromptCalls("P", 3), new PromptCalls("Q", 1)),
                summary(t, null).items());
    }

    @Test
    void viewerReadsUsage() {
        // Bug: usage is gated on the manage permission, so a viewer sees an error on the Prompts page.
        Tenant t = tenant("usage-viewer");
        String id = prompt(t.pid(), "summarize", 1);
        Principal viewer = tenants.upsertUserFromWorkos(
                "user_usage_viewer_" + System.nanoTime(),
                "usage-viewer+" + System.nanoTime() + "@example.com",
                "v",
                null);
        memberships.insert(OrgMembership.of(t.orgId(), viewer.id(), Role.VIEWER.wire(), NOW.toString()));
        TenantContext session = new TenantContext(viewer.id(), viewer.email(), null, null, null, null);

        assertEquals(
                7, ok(controller().usage(session, t.org(), t.proj(), id, null)).days());
    }

    @Test
    void writeKeyCannotReadUsage() {
        // Bug: an ingest key, which every bearer key passes RBAC as, reads prompt usage.
        Tenant t = tenant("usage-key-scope");
        String id = prompt(t.pid(), "summarize", 1);

        TessaryException ex = assertThrows(
                TessaryException.class, () -> controller().usage(key(t, KeyScope.WRITE), t.org(), t.proj(), id, null));
        assertEquals(PromptsError.WRONG_KEY_SCOPE, ex.error());
        assertThrows(
                TessaryException.class, () -> controller().summary(key(t, KeyScope.WRITE), t.org(), t.proj(), null));
        assertEquals(
                7,
                ok(controller().usage(key(t, KeyScope.QUERY), t.org(), t.proj(), id, null))
                        .days());
    }

    private Tenant tenant(String slug) {
        var fix = TenantFixture.bootstrap(tenants, slug);
        return new Tenant(
                new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null),
                fix.org().slug(),
                fix.project().slug(),
                fix.project().id(),
                fix.org().id());
    }

    private static TenantContext key(Tenant t, KeyScope scope) {
        return new TenantContext("key-principal", null, t.orgId(), t.pid(), "member", "tok-1", scope);
    }

    /** A prompt row and its versions, written directly: usage reads the tables, not the prompt service. */
    private String prompt(String pid, String name, int... versions) {
        String id = Ids.ulid();
        jdbc.sql("INSERT INTO prompt (id, project_id, name, type, created_by) VALUES (:id, :pid, :name, 'text', 'u')")
                .param("id", id)
                .param("pid", pid)
                .param("name", name)
                .update();
        for (int version : versions) {
            jdbc.sql("INSERT INTO prompt_version (id, project_id, prompt_id, version, body, created_by, created_via)"
                            + " VALUES (:id, :pid, :prompt, :version, '\"hi\"'::jsonb, 'u', 'ui')")
                    .param("id", Ids.ulid())
                    .param("pid", pid)
                    .param("prompt", id)
                    .param("version", version)
                    .update();
        }
        return id;
    }

    /**
     * One linked llm call in a trace of its own, settled. Tokens are 10 in and 5 out; a priced call costs 0.001 in and
     * 0.0005 out. The fixture writes no latency, so it is set here.
     */
    private SpanRow call(
            String pid,
            String name,
            @Nullable String version,
            Instant at,
            long latencyMs,
            boolean error,
            boolean unpriced) {
        var seed = fx.spanSeed(pid).prompt(name, version).at(at).usage(10L, 5L);
        if (error) seed.status("error");
        if (!unpriced) seed.cost("0.001", "0.0005", SpanRow.CostSource.INFERRED);
        SpanRow row = seed.write();
        jdbc.sql("UPDATE span SET latency_ms = :ms WHERE project_id = :pid AND trace_id = :tid AND id = :sid")
                .param("ms", latencyMs)
                .param("pid", pid)
                .param("tid", row.traceId())
                .param("sid", row.id())
                .update();
        settle(row);
        return row;
    }

    private void settle(SpanRow row) {
        Instant started = Instant.parse(row.startedAt());
        fx.rollup(row.projectId(), row.traceId(), started, started.plusSeconds(1), true);
    }

    private PromptUsage usage(Tenant t, String promptId, @Nullable Integer days) {
        return ok(controller().usage(t.ctx(), t.org(), t.proj(), promptId, days));
    }

    private PromptUsageDtos.PromptUsageSummary summary(Tenant t, @Nullable Integer days) {
        return ok(controller().summary(t.ctx(), t.org(), t.proj(), days));
    }

    private static <T> T ok(ApiResponse<T> response) {
        T data = response.data();
        assertNotNull(data, "the envelope carried no data");
        return data;
    }
}
