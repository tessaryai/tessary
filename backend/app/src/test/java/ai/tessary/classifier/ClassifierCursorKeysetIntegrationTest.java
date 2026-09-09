// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.StubEncoderScorerConfig;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TurnGrainTestDetectionConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression for the keyset sweep cursor: with a batch size smaller than a group of spans that share
 * the <em>exact same</em> {@code created_at} (the realistic bulk-ingest case — {@code created_at}
 * defaults to {@code now()}, which in Postgres is TRANSACTION time, so one ingest batch stamps every
 * row in it identically), a {@code created_at}-only {@code >} cursor would advance past the timestamp
 * at a batch boundary and silently drop the rest of that group.
 *
 * <p>The keyset is a TRIPLE in v2 — {@code (created_at, trace_id, id)} — because a span id is unique
 * only within its trace, and the cursor carries the two id halves as the composite handle
 * {@code "<trace_id>:<span_id>"}. A handle written as a bare span id has no colon, parses as "no
 * cursor", and restarts the sweep from page one on every tick; this test would still pass on that bug,
 * so the assertion is joined by one on the stored cursor itself.
 */
@SpringBootTest
@Import({StubEncoderScorerConfig.class, TurnGrainTestDetectionConfig.class})
class ClassifierCursorKeysetIntegrationTest {

    private static final int BATCH = 2;
    private static final int SAME_TS_COUNT = 5; // > BATCH, so a boundary falls inside the identical-ts group

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        r.add("tessary.classifier.batch-size", () -> String.valueOf(BATCH));
    }

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ClassifierWorker worker;

    @Autowired
    ClassifierService service;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    void keysetCursorVisitsEverySpanSharingOneTimestamp() {
        // Frustration is one of the four paid classifiers and OFF by default in an open build
        // (#887/#888). It is the only TURN-grain built-in, which is exactly the shape this cursor
        // regression needs (see seedAndFindFrustration below), so this grants it rather than repointing
        // to another classifier — there is no open substitute with the same grain.
        String pid = TenantFixture.bootstrap(
                        tenants, "signal-keyset", org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        Instant now = Instant.now();

        // SAME_TS_COUNT user-facing TURNS, each its own trace with a root llm span, each carrying a
        // frustration keyword so the built-in Frustration detector fires exactly once per turn.
        // Frustration is turn-grain, so the units that must survive the batch boundary are turns —
        // several root spans under ONE turn would (correctly) collapse to a single detection and would
        // not exercise the cursor at all.
        //
        // ONE CONVERSATION PER TURN, which this test used to share. The turn-grain sweep skips a turn
        // whose conversation is already flagged at high (a conversation is one event, not one per turn
        // — see ClassifierWorker#suppressAlreadyFlaggedConversations), so five frustrated turns in one
        // conversation now correctly produce ONE detection. That is the intended behaviour and it makes
        // detection count useless as a proxy for cursor coverage WITHIN a conversation. Separate
        // conversations restore the proxy: each turn is independently flaggable, so a missing detection
        // again means a dropped span rather than a suppressed duplicate. What this test is about — that
        // the keyset cursor drops nothing at a created_at tie — is unchanged.
        //
        // Each conversation gets its own warm-up turn FIRST, at an earlier timestamp: frustration skips
        // a conversation's opener (context_min_prior_user_turns=1 — the agent has not acted yet), so
        // without a preceding turn the frustrated turn would be gated out and this test would read a
        // dropped span as a cursor bug. The warm-ups sit outside the group's timestamp on purpose.
        Instant earlier = now.minusSeconds(60);
        List<SpanRef> group = new ArrayList<>();
        for (int i = 0; i < SAME_TS_COUNT; i++) {
            String sessionId = SubstrateV2Fixtures.sessionId();
            SpanRef warmup = seedTurn(pid, sessionId, "hello, i have a question", earlier);
            stampCreatedAt(pid, List.of(warmup), earlier);
            group.add(seedTurn(pid, sessionId, "this is frustrating, you're not listening", now));
        }
        // One created_at across the whole group — the boundary case. Written explicitly rather than
        // relied upon: the fixture writes each span in its own transaction, so the default now() would
        // give every row a distinct stamp and the batch boundary would never land inside a tie.
        stampCreatedAt(pid, group, now);

        // Several ticks: each tick advances the cursor by at most one BATCH per sweep, so the group spans
        // multiple sweeps and exercises the batch boundary inside the identical-timestamp run.
        ClassifierRow frustration = seedAndFindFrustration(pid);
        for (int tick = 0; tick < SAME_TS_COUNT + 2; tick++) {
            service.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
            worker.tick();
            if (service.eventsForClassifier(pid, frustration.id(), 100).size() >= SAME_TS_COUNT) break;
            sleep(200);
        }

        assertEquals(
                SAME_TS_COUNT,
                service.eventsForClassifier(pid, frustration.id(), 100).size(),
                "every span sharing one created_at is detected — the keyset cursor drops none at a boundary");

        // The cursor is only doing its job if it PARSES. A sweep that stamps a bare span id restarts from
        // page one every tick and still reaches every row, so the count above cannot tell the two apart.
        List<String> cursors = jdbc.sql(
                        "SELECT cursor_id FROM job WHERE project_id = :pid AND kind = 'classifier' AND cursor_id IS NOT NULL")
                .param("pid", pid)
                .query((rs, n) -> rs.getString("cursor_id"))
                .list();
        assertEquals(
                List.of(),
                cursors.stream().filter(c -> !c.contains(":")).toList(),
                "every stamped cursor is the composite <trace_id>:<span_id> handle the reader parses back");
    }

    private SpanRef seedTurn(String pid, String sessionId, String userText, Instant at) {
        return fx.spanSeed(pid)
                .traceId(SubstrateV2Fixtures.traceId())
                .sessionId(sessionId)
                .kind("llm")
                .name("chat")
                .model("gpt-x")
                .at(at)
                .payload(ClassifierObservations.userInput(userText), "ok")
                .writeRef();
    }

    /** Give a set of spans one identical {@code created_at} — the single-transaction ingest shape. */
    private void stampCreatedAt(String pid, List<SpanRef> refs, Instant at) {
        for (SpanRef ref : refs) {
            jdbc.sql("UPDATE span SET created_at = :at::timestamptz "
                            + "WHERE project_id = :pid AND trace_id = :tid AND id = :sid")
                    .param("at", at.toString())
                    .param("pid", pid)
                    .param("tid", ref.traceId())
                    .param("sid", ref.spanId())
                    .update();
        }
    }

    /** Run a tick so the catalog seeds, then resolve the Frustration definition. */
    private ClassifierRow seedAndFindFrustration(String pid) {
        for (int i = 0; i < 50; i++) {
            service.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
            worker.tick();
            var maybe = signals.findByKey(pid, "frustration");
            if (maybe.isPresent()) return maybe.get();
            sleep(100);
        }
        throw new IllegalStateException("frustration built-in was not seeded");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
