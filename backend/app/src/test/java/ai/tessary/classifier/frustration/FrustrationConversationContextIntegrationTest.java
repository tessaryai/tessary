// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.frustration.FrustrationRateRepository.ConversationContext;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The turns a flagged one is shown with: the ones before it in its own conversation, in the order they
 * happened rather than the order they were stored, never a later turn and never another conversation's.
 */
@SpringBootTest
class FrustrationConversationContextIntegrationTest {

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    FrustrationRateRepository rates;

    @Autowired
    TenantService tenants;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads);
    }

    @Test
    void theTwoTurnsBeforeTheFlaggedOneOldestFirst() {
        String pid = TenantFixture.bootstrap(tenants, "fr-context").project().id();
        Instant base = Instant.now();
        String session = SubstrateV2Fixtures.sessionId();
        String other = SubstrateV2Fixtures.sessionId();

        // Stored latest first, as an upload can deliver them.
        SpanRef later = turn(pid, session, base.plusMillis(5_000));
        SpanRef flagged = turn(pid, session, base.plusMillis(4_000));
        SpanRef third = turn(pid, session, base.plusMillis(3_000));
        SpanRef second = turn(pid, session, base.plusMillis(2_000));
        turn(pid, session, base.plusMillis(1_000));
        turn(pid, other, base.plusMillis(3_500));

        Map<String, ConversationContext> context =
                rates.conversationContext(pid, List.of(flagged.traceId()), FrustrationEvidence.CONTEXT_TURNS_BEFORE);

        ConversationContext ctx = Objects.requireNonNull(context.get(flagged.traceId()));
        assertEquals(List.of(second.traceId(), third.traceId()), ctx.priorTraceIds());
        assertEquals(session, ctx.sessionId());
        assertTrue(!ctx.priorTraceIds().contains(later.traceId()), "a later turn is never context");
    }

    @Test
    void aConversationsFirstTurnHasNoPriorTurns() {
        String pid =
                TenantFixture.bootstrap(tenants, "fr-context-first").project().id();
        SpanRef first = turn(pid, SubstrateV2Fixtures.sessionId(), Instant.now());

        ConversationContext ctx = Objects.requireNonNull(
                rates.conversationContext(pid, List.of(first.traceId()), 2).get(first.traceId()));

        assertEquals(List.of(), ctx.priorTraceIds());
    }

    private SpanRef turn(String pid, String session, Instant at) {
        return fx.turn(
                pid, SubstrateV2Fixtures.traceId(), session, at, "[{\"role\":\"user\",\"content\":\"q\"}]", null);
    }
}
