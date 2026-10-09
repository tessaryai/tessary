// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.errors.VersionError;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.version.CommitLineageService.NodeKind;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The commit-SHA lineage spine: a session, turn, trace, or span resolves to the {@code project_version} that caused
 * it. {@code verdict}, {@code observer_alert}, and {@code diff_classification} are gone; what remains is the direct
 * FK on {@code trace}/{@code span} and the session's derived MAX.
 */
@SpringBootTest
class CommitLineageTest {

    @Autowired
    CommitLineageService lineage;

    @Autowired
    ProjectVersionRepository versions;

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository v2traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    ProjectVersionController controller;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, v2traces, spans, payloads);
    }

    /**
     * Grains resolve to their own row's version. Trace and span carry the column; a session can be resumed across
     * deploys, so it resolves as the MAX over its traces.
     */
    @Test
    void substrateGrainsResolveToTheVersionOnTheirOwnRow() {
        var fix = TenantFixture.bootstrap(tenants, "lineage-substrate");
        String pid = fix.project().id();
        String now = Instant.now().toString();
        ProjectVersionRow ver =
                versions.findOrMaterialize(pid, "sha-substrate", ProjectVersionRow.REASON_PIPELINE_SYNC);

        String sessionId = SubstrateV2Fixtures.sessionId();
        String traceId = SubstrateV2Fixtures.traceId();
        SpanRef span = fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(sessionId)
                .projectVersionId(ver.id())
                .kind("llm")
                .name("step")
                .at(Instant.parse(now))
                .payload("in", "out")
                .writeRef();

        assertSha(ver, lineage.resolve(pid, NodeKind.SESSION, sessionId), "session resolves as MAX over its traces");
        assertSha(ver, lineage.resolve(pid, NodeKind.TURN, traceId), "a turn IS a trace, under the legacy name");
        assertSha(ver, lineage.resolve(pid, NodeKind.TRACE, traceId), "trace resolves via its own column");
        assertTrue(
                lineage.resolve(pid, NodeKind.SPAN, span.spanId()).isEmpty(),
                "a bare span id resolves to nothing rather than to whichever trace reused it");
    }

    /** A wrong commit for a resolvable node, or a 500 for an unknown kind or unresolvable node. */
    @Test
    void lineageEndpointResolvesANodeAndNamesWhyItCannot() {
        var fix = TenantFixture.bootstrap(tenants, "lineage-endpoint");
        String pid = fix.project().id();
        ProjectVersionRow ver = versions.findOrMaterialize(pid, "sha-endpoint", ProjectVersionRow.REASON_PIPELINE_SYNC);
        String traceId = SubstrateV2Fixtures.traceId();
        fx.spanSeed(pid)
                .traceId(traceId)
                .sessionId(SubstrateV2Fixtures.sessionId())
                .projectVersionId(ver.id())
                .kind("llm")
                .name("step")
                .at(Instant.now())
                .payload("in", "out")
                .writeRef();
        TenantContext owner = new TenantContext(fix.user().id(), null, null, null, null, null);
        String org = fix.org().slug();
        String project = fix.project().slug();

        assertEquals(
                "sha-endpoint",
                Objects.requireNonNull(controller
                                .lineage(owner, org, project, "trace", traceId)
                                .data())
                        .commitSha());
        assertEquals(
                VersionError.UNKNOWN_NODE_KIND,
                assertThrows(TessaryException.class, () -> controller.lineage(owner, org, project, "verdict", traceId))
                        .error());
        assertEquals(
                VersionError.LINEAGE_UNRESOLVED,
                assertThrows(
                                TessaryException.class,
                                () -> controller.lineage(owner, org, project, "trace", "no-such-trace"))
                        .error());
    }

    private static void assertSha(ProjectVersionRow expected, Optional<ProjectVersionRow> actual, String message) {
        assertTrue(actual.isPresent(), message);
        assertEquals(expected.commitSha(), actual.get().commitSha(), message);
        assertEquals(expected.id(), actual.get().id(), message);
    }
}
