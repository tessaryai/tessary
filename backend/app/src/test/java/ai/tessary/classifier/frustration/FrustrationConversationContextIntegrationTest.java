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

    private static final String CHAT = "cs-chat";

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

        Map<String, ConversationContext> context = rates.conversationContext(
                pid, CHAT, List.of(flagged.traceId()), FrustrationEvidence.CONTEXT_TURNS_BEFORE);

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

        ConversationContext ctx =
                Objects.requireNonNull(rates.conversationContext(pid, CHAT, List.of(first.traceId()), 2)
                        .get(first.traceId()));

        assertEquals(List.of(), ctx.priorTraceIds());
    }

    /**
     * The turns shown are the ones the classifier read: those that reached the flagged call site. Read across call
     * sites, a turn that only ran a background call stands in for a turn of the chat.
     */
    @Test
    void aTurnThatNeverReachedTheCallSiteIsNotContext() {
        String pid = TenantFixture.bootstrap(tenants, "fr-context-call-site")
                .project()
                .id();
        Instant base = Instant.now();
        String session = SubstrateV2Fixtures.sessionId();

        SpanRef first = turn(pid, session, base.plusMillis(1_000));
        turn(pid, session, base.plusMillis(2_000), "cs-memory");
        SpanRef flagged = turn(pid, session, base.plusMillis(3_000));

        ConversationContext ctx =
                Objects.requireNonNull(rates.conversationContext(pid, CHAT, List.of(flagged.traceId()), 2)
                        .get(flagged.traceId()));

        assertEquals(List.of(first.traceId()), ctx.priorTraceIds());
    }

    private SpanRef turn(String pid, String session, Instant at) {
        return turn(pid, session, at, CHAT);
    }

    /** A turn whose one {@code llm} span is on {@code callSite}. */
    private SpanRef turn(String pid, String session, Instant at, String callSite) {
        return fx.spanSeed(pid)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(session)
                .callSiteId(callSite)
                .at(at)
                .payload("[{\"role\":\"user\",\"content\":\"q\"}]", null)
                .writeRef();
    }
}
