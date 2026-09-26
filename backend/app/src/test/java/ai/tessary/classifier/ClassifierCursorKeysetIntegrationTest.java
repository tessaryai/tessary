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
import ai.tessary.testsupport.ClassifierConversations;
import ai.tessary.testsupport.ClassifierObservations;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.StubDecisionClientConfig;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
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
 * Regression for the keyset sweep cursor. One ingest transaction stamps every span with the same {@code created_at},
 * so with a batch smaller than that group a {@code created_at}-only {@code >} cursor skips the rest of the group at a
 * batch boundary.
 *
 * <p>The keyset is {@code (created_at, trace_id, id)}, carried as {@code "<trace_id>:<span_id>"}. A bare span id
 * parses as "no cursor" and restarts every tick, which the count alone would miss, so the stored cursor is asserted
 * too.
 */
@SpringBootTest
@Import(StubDecisionClientConfig.class)
class ClassifierCursorKeysetIntegrationTest {

    private static final int BATCH = 2;
    private static final int SAME_TS_COUNT = 5; // > BATCH, so a boundary falls inside the group

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
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
        // Frustration is the only turn-grain built-in, the shape this regression needs. It seeds disabled, so the
        // test turns it on.
        String pid = TenantFixture.bootstrap(
                        tenants, "signal-keyset", org -> capabilities.grant(org.id(), Capability.FRUSTRATION))
                .project()
                .id();
        Instant now = Instant.now();

        // One frustrated turn per trace, each in its own conversation (a flagged conversation suppresses later
        // turns), so a missing detection means a dropped span. Each conversation gets two earlier warm-up exchanges,
        // which frustration needs before it sends a turn.
        List<SpanRef> group = new ArrayList<>();
        for (int i = 0; i < SAME_TS_COUNT; i++) {
            String sessionId = SubstrateV2Fixtures.sessionId();
            List<SpanRef> warmups = ClassifierConversations.seedPreamble(fx, pid, sessionId, now.toString());
            stampCreatedAt(pid, warmups.subList(0, 1), now.minusSeconds(120));
            stampCreatedAt(pid, warmups.subList(1, 2), now.minusSeconds(60));
            group.add(seedTurn(pid, sessionId, "this is frustrating, you're not listening", now));
        }
        // One explicit created_at: the fixture writes each span in its own transaction, so now() would never tie.
        stampCreatedAt(pid, group, now);

        // Each tick advances at most one BATCH, so the group spans several sweeps; three turns per conversation
        // triple the pages.
        ClassifierRow frustration = seedAndFindFrustration(pid);
        for (int tick = 0; tick < 3 * SAME_TS_COUNT + 2; tick++) {
            service.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
            worker.tick();
            if (service.eventsForClassifier(pid, frustration.id(), null, 100).size() >= SAME_TS_COUNT) break;
            sleep(200);
        }

        assertEquals(
                SAME_TS_COUNT,
                service.eventsForClassifier(pid, frustration.id(), null, 100).size(),
                "every span sharing one created_at is detected — the keyset cursor drops none at a boundary");

        // A bare span id restarts from page one every tick and still reaches every row, so check the cursor parses.
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

    /** Give a set of spans one identical {@code created_at}: the single-transaction ingest shape. */
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

    /** Seed the catalog, then resolve the Frustration definition and turn it on. */
    private ClassifierRow seedAndFindFrustration(String pid) {
        for (int i = 0; i < 50; i++) {
            service.seedBuiltIns(pid); // idempotent
            var maybe = ClassifierRows.byKey(signals, pid, "frustration");
            if (maybe.isPresent()) return service.setEnabled(pid, maybe.get().id(), true);
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
