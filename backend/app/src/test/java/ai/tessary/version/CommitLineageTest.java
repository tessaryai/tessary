// SPDX-License-Identifier: Apache-2.0
package ai.tessary.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance for the commit-SHA lineage spine: a substrate grain — session, turn, trace or span —
 * resolves to the exact {@code project_version} (commit SHA) that caused it.
 *
 * <p><b>Three node kinds are gone, and with them the raw-SHA shape.</b> {@code verdict},
 * {@code observer_alert} and {@code diff_classification} were all resolvable here; grading and the
 * observer are gone, and {@code observer_alert.project_version_sha} was the only raw-SHA provenance
 * the spine ever had. What is asserted below is the whole of what remains: the direct-FK shape on
 * {@code trace}/{@code span}, and the session's derived MAX.
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

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, v2traces, spans, payloads);
    }

    /**
     * Substrate grains resolve to the version their own row carries — no parent chain left to walk.
     *
     * <p>v1 hung {@code project_version_id} on the session and made every grain below it climb an ltree
     * to find one. In v2 the trace and the span each carry the column, denormalized at ingest, and the
     * SESSION is the one grain with no stamp of its own — a session can be resumed days later across
     * several deploys, so it resolves as the MAX over its traces instead of the other way round.
     */
    @Test
    void substrateGrainsResolveToTheVersionOnTheirOwnRow() {
        var fix = TenantFixture.bootstrap(tenants, "lineage-substrate");
        String pid = fix.project().id();
        String now = Instant.now().toString();
        ProjectVersionRow ver = versions.findOrMaterialize(pid, "sha-substrate", ProjectVersionRow.REASON_BENCHMARK);

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
        assertSha(
                ver,
                lineage.resolve(pid, NodeKind.SPAN, traceId, span.spanId()),
                "a span resolves via its own column, addressed by the producer PAIR");
        assertTrue(
                lineage.resolve(pid, NodeKind.SPAN, span.spanId()).isEmpty(),
                "and a bare span id resolves to nothing rather than to whichever trace reused it");
    }

    private static void assertSha(ProjectVersionRow expected, Optional<ProjectVersionRow> actual, String message) {
        assertTrue(actual.isPresent(), message);
        assertEquals(expected.commitSha(), actual.get().commitSha(), message);
        assertEquals(expected.id(), actual.get().id(), message);
    }
}
