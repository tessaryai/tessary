// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SessionRow;
import ai.tessary.storage.SpanRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The fields an evidence row carries beyond its span, computed at read time against the real schema: the token split,
 * user, position in the session, payload size and availability, and the short attribute list.
 */
@SpringBootTest
record FindingEvidenceSpanPageIntegrationTest(
        FindingRepository findings,
        FindingEvidenceRepository evidence,
        SubstrateV2Fixtures fx,
        SessionRepository sessions,
        TenantService tenants,
        JdbcClient jdbc) {

    private static final Instant T0 = Instant.parse("2026-02-03T10:00:00Z");

    /** Catches a whole-run row reporting its root span's own usage, or a span row reporting the trace's. */
    @Test
    @DisplayName("a whole-run row carries the trace rollup's token split, a span row its own")
    void wholeRunRow_tokenSplitFromTraceRollup() {
        String pid = project("span-page-token-split");
        String findingId = firing(pid, "gram-token-split");
        String traceId = "trace-split";
        SpanRow root = fx.spanSeed(pid).traceId(traceId).at(T0).write();
        SpanRow child = fx.spanSeed(pid)
                .traceId(traceId)
                .parentSpanId(root.id())
                .at(T0.plusSeconds(1))
                .write();
        fx.withUsage(root, 100L, 10L, 5L, 2L, 3L);
        fx.withUsage(child, 200L, 20L, null, null, 7L);
        assertTrue(fx.rollup(pid, traceId));
        record(
                pid,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                FindingEvidenceRepository.Ref.trace(traceId),
                FindingEvidenceRepository.Ref.span(traceId, child.id()));

        List<FindingEvidenceRepository.SpanRef> rows = page(pid, findingId);

        FindingEvidenceRepository.SpanRef run = rows.get(0);
        assertNull(run.spanId());
        assertEquals(
                List.of(300L, 30L, 5L, 2L, 10L, 347L),
                Arrays.asList(
                        run.inputTokens(),
                        run.outputTokens(),
                        run.cacheReadTokens(),
                        run.cacheWriteTokens(),
                        run.reasoningTokens(),
                        run.totalTokens()));
        FindingEvidenceRepository.SpanRef step = rows.get(1);
        assertEquals(child.id(), step.spanId());
        assertEquals(200L, step.inputTokens());
        assertEquals(20L, step.outputTokens());
        assertNull(step.cacheReadTokens(), "not reported is null, never 0");
        assertNull(step.cacheWriteTokens());
        assertEquals(7L, step.reasoningTokens());
    }

    /** Catches a prompt-carrier key or the request-model duplicate reaching the short list, and an unmasked value. */
    @Test
    @DisplayName("the short attribute list keeps identity keys, drops payload carriers and masks values")
    void shortList_dropsPayloadCarrierKeys() {
        String pid = project("span-page-short-list");
        String findingId = firing(pid, "gram-short-list");
        String attributes = "{\"service.instance.id\":\"i-1\",\"service.version\":\"2.3.1\","
                + "\"gen_ai.response.model\":\"m-2026-01\",\"gen_ai.request.model\":\"m\","
                + "\"gen_ai.input.messages\":\"[{\\\"role\\\":\\\"user\\\",\\\"content\\\":\\\"hi\\\"}]\","
                + "\"gen_ai.prompt.0.content\":\"hi\","
                + "\"host.name\":\"ghp_aB3dE5gH7jK9mN1pQ3sT5vX7zA9cE1gI3kM5\"}";
        SpanRow span = fx.spanSeed(pid).at(T0).payload("q", "a", attributes).write();
        record(
                pid,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                FindingEvidenceRepository.Ref.span(span.traceId(), span.id()));

        FindingEvidenceRepository.SpanRef row = page(pid, findingId).get(0);

        assertEquals(
                Map.of(
                        "service.instance.id", "i-1",
                        "service.version", "2.3.1",
                        "gen_ai.response.model", "m-2026-01",
                        "host.name", "ghp_…3kM5"),
                row.attributes());
    }

    /**
     * Catches {@code length()} where bytes are meant (5 for "héllo"), "available" read from non-null text so a payload
     * with attributes and no text reads as aged out, and a stale true after the purge.
     */
    @Test
    @DisplayName("payloadAvailable means the payload row exists, and sizes are bytes")
    void agedOutPayload_payloadAvailableFalse() {
        String pid = project("span-page-payload");
        String findingId = firing(pid, "gram-payload");
        SpanRow a = fx.spanSeed(pid).at(T0).payload("héllo", "ok", null).write();
        SpanRow b = fx.spanSeed(pid)
                .at(T0.plusSeconds(1))
                .payload(null, null, "{\"service.name\":\"api\"}")
                .write();
        record(
                pid,
                findingId,
                FindingEvidenceRow.Role.WITNESS,
                FindingEvidenceRepository.Ref.span(a.traceId(), a.id()),
                FindingEvidenceRepository.Ref.span(b.traceId(), b.id()));

        List<FindingEvidenceRepository.SpanRef> before = page(pid, findingId);

        assertTrue(before.get(0).payloadAvailable());
        assertEquals(6L, before.get(0).inputBytes());
        assertEquals(2L, before.get(0).outputBytes());
        assertTrue(before.get(1).payloadAvailable(), "a payload with attributes and no text can still be opened");
        assertNull(before.get(1).inputBytes());
        assertNull(before.get(1).outputBytes());
        assertEquals(Map.of("service.name", "api"), before.get(1).attributes());

        jdbc.sql("DELETE FROM span_payload WHERE project_id = :pid")
                .param("pid", pid)
                .update();
        List<FindingEvidenceRepository.SpanRef> after = page(pid, findingId);

        for (FindingEvidenceRepository.SpanRef row : after) {
            assertFalse(row.payloadAvailable(), row.toString());
            assertNull(row.inputBytes());
            assertNull(row.outputBytes());
            assertEquals(Map.of(), row.attributes());
            assertNull(row.inputPreview());
        }
    }

    /** Catches a session row with no start time and no user, which a span-only join gives it. */
    @Test
    @DisplayName("a session row carries its start, its user and its turn count")
    void sessionRow_hasStartedAtAndUser() {
        String pid = project("span-page-session");
        String findingId = firing(pid, "gram-session");
        sessions.getOrCreateAll(
                List.of(new SessionRow(pid, "sess-u", "user-7", T0.toString(), T0.toString(), T0.toString(), false)));
        fx.turn(pid, "trace-1", "sess-u", T0, "a", "b");
        fx.turn(pid, "trace-2", "sess-u", T0.plusSeconds(60), "c", "d");
        record(pid, findingId, FindingEvidenceRow.Role.WITNESS, FindingEvidenceRepository.Ref.session("sess-u"));

        FindingEvidenceRepository.SpanRef row = page(pid, findingId).get(0);

        assertEquals(T0.toString(), row.startedAt());
        assertEquals("user-7", row.userId());
        assertEquals(2, row.sessionTraces());
        assertNull(row.traceIndex());
        assertNull(row.traceId());
        assertNull(row.spanId());
        assertFalse(row.payloadAvailable());
        assertEquals(Map.of(), row.attributes());
    }

    /**
     * Catches a sub-agent trace counted as a turn (d=4, 4 turns), a tie broken on start time alone (a and b share a
     * position), and a sub-agent row given a position.
     */
    @Test
    @DisplayName("traceIndex counts turns only and breaks a start-time tie on the trace id")
    void traceIndex_ignoresSubAgentTraces_tieBreaksOnId() {
        String pid = project("span-page-position");
        String findingId = firing(pid, "gram-position");
        fx.trace(pid, "trace-a", "sess-p", T0);
        fx.trace(pid, "trace-b", "sess-p", T0);
        fx.trace(pid, "trace-c", "sess-p", T0.plusSeconds(1));
        fx.detachUnder(pid, "trace-c", "trace-a");
        fx.trace(pid, "trace-d", "sess-p", T0.plusSeconds(2));
        record(
                pid,
                findingId,
                FindingEvidenceRow.Role.MEMBER,
                FindingEvidenceRepository.Ref.trace("trace-a"),
                FindingEvidenceRepository.Ref.trace("trace-b"),
                FindingEvidenceRepository.Ref.trace("trace-c"),
                FindingEvidenceRepository.Ref.trace("trace-d"));

        List<FindingEvidenceRepository.SpanRef> rows = page(pid, findingId);

        assertEquals(
                List.of("trace-a", "trace-b", "trace-c", "trace-d"),
                rows.stream().map(FindingEvidenceRepository.SpanRef::traceId).toList());
        assertEquals(
                Arrays.asList(1, 2, null, 3),
                rows.stream().map(FindingEvidenceRepository.SpanRef::traceIndex).toList());
        assertEquals(
                List.of(3, 3, 3, 3),
                rows.stream()
                        .map(FindingEvidenceRepository.SpanRef::sessionTraces)
                        .toList());
    }

    private List<FindingEvidenceRepository.SpanRef> page(String pid, String findingId) {
        return evidence.spanPage(pid, findingId, null, 10, null, false, false).rows();
    }

    private void record(String pid, String findingId, String role, FindingEvidenceRepository.Ref... refs) {
        evidence.record(pid, findingId, role, List.of(refs), Instant.now().toString());
    }

    private String project(String name) {
        return TenantFixture.bootstrap(tenants, name).project().id();
    }

    private static final String ARMED_PAYLOAD = "{\"cause_kind\":\"" + FindingRow.Cause.ARMED_WINDOW + "\"}";

    private String firing(String pid, String gram) {
        String now = Instant.now().toString();
        return Objects.requireNonNull(findings.recordArmedWindow(
                        Ids.ulid(),
                        pid,
                        BuiltInDetector.Kind.REGEX,
                        "clf-" + gram,
                        gram,
                        1,
                        "cs-a",
                        ARMED_PAYLOAD,
                        now,
                        now,
                        now,
                        now))
                .findingId();
    }
}
