// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.gate.PreDeployCheckRepository;
import ai.tessary.gate.PreDeployCheckRow;
import ai.tessary.gate.PreDeployCheckService;
import ai.tessary.model.TouchedSurface;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end acceptance for the production-signal → pre-deploy loop: with the loop enabled, a
 * genuinely-NEW production signal discovered by the async {@link ClassifierWorker} sweep auto-registers
 * a routed {@code pre_deploy_check} tied to the surfaces the signal definition implicates (its explicit
 * cold-start {@code config_json.surfaces}). Re-discovery is idempotent. Run against the real pgvector
 * Postgres (Testcontainers) so the schema + the UNIQUE/ON-CONFLICT idempotency run for real.
 *
 * <p>{@code classifier} is the only source the loop has. The user-feedback source, and the four cases that
 * covered it, went with the substrate's feedback table.
 */
@SpringBootTest
class PreDeploySignalLoopIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.predeploy.enabled", () -> "true"); // activate the loop (write + read)
    }

    @Autowired
    ClassifierWorker worker;

    @Autowired
    ai.tessary.classifier.ClassifierService signalService;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    PreDeployCheckService preDeployChecks;

    @Autowired
    PreDeployCheckRepository checks;

    @Autowired
    TenantService tenants;

    @Autowired
    SessionRepository sessions;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SpanRepository spans;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    @Test
    void newSignalDiscoveryRegistersSurfaceScopedPreDeployCheck_idempotently() {
        String pid =
                TenantFixture.bootstrap(tenants, "predeploy-loop").project().id();
        String now = Instant.now().toString();

        // A signal definition that, when it fires, implicates two LLM-product surfaces — the explicit
        // cold-start mapping (config_json.surfaces). Detector = regex, the seam user-authored signals
        // actually run on: it fires on the phrase below, independent of any built-in's lifecycle.
        String classifierId = Ids.ulid();
        signals.insert(new ClassifierRow(
                classifierId,
                pid,
                "tool_failure_probe",
                "Tool Failure Probe",
                "A probe signal scoped to the tool + agent-loop surfaces.",
                "regex",
                "{\"surfaces\":[\"tool_definition\",\"agent_loop\"],\"phrases\":[\"upstream exploded\"]}",
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));

        // Substrate: a session and one turn whose tool span carries the phrase in its payload output.
        SpanRef tool = seedFailingToolSpan(pid, "upstream exploded", "HTTP 500 upstream");

        signalService.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
        worker.tick();
        awaitChecks(pid, 2);

        // The discovery registered one pre-deploy check per implicated surface, status=active.
        List<PreDeployCheckRow> rows = checks.listByProject(pid);
        assertEquals(2, rows.size(), "one check per implicated surface");
        for (PreDeployCheckRow row : rows) {
            assertEquals(classifierId, row.classifierId(), "provenance: the discovering signal");
            assertEquals(PreDeployCheckRow.Status.ACTIVE, row.status(), "registered active");
            assertTrue(TouchedSurface.parse(row.surface()).isPresent(), "surface is a valid TouchedSurface wire name");
        }
        List<String> surfaces = preDeployChecks.activeSurfaces(pid);
        assertTrue(
                surfaces.contains(TouchedSurface.TOOL_DEFINITION.wire())
                        && surfaces.contains(TouchedSurface.AGENT_LOOP.wire()),
                "the implicated surfaces are the read-side join a future PR unions in: " + surfaces);

        // Idempotency: a second tick over the same substrate registers no new checks.
        signalService.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
        worker.tick();
        sleep(1_000);
        assertEquals(2, checks.listByProject(pid).size(), "re-discovery of the same signal is a no-op");

        // The dismiss lifecycle drops the surface from the active read-side join without touching the signal.
        preDeployChecks.dismiss(pid, rows.get(0).id());
        assertEquals(1, preDeployChecks.activeSurfaces(pid).size(), "a dismissed check leaves the active surface set");
    }

    @Test
    void signalWithNoSurfaceMappingRegistersNothing() {
        String pid =
                TenantFixture.bootstrap(tenants, "predeploy-noop").project().id();
        String now = Instant.now().toString();

        // A signal with NO explicit surfaces and no learned failure mode → the honest no-op.
        signals.insert(new ClassifierRow(
                Ids.ulid(),
                pid,
                "unmapped_probe",
                "Unmapped Probe",
                null,
                "regex",
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));

        seedFailingToolSpan(pid, "upstream exploded", "boom");

        signalService.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
        worker.tick();
        sleep(1_500); // let the async sweep run; nothing should be registered

        assertTrue(
                checks.listByProject(pid).isEmpty(),
                "a signal with no surface mapping registers no check — never a fabricated surface");
    }

    /**
     * A settled turn whose {@code tool} span carries {@code phrase} in its payload output, with the
     * {@code tool_call} row hung off it by producer keys — what the async sweep reads to discover a signal.
     *
     * @return the span's producer identity, so a caller can hang more evidence off the same span
     */
    private SpanRef seedFailingToolSpan(String pid, String phrase, String errorType) {
        Instant at = Instant.now();
        String sessionId = SubstrateV2Fixtures.sessionId();
        SpanRef tool = fx.spanSeed(pid)
                .sessionId(sessionId)
                .kind("tool")
                .name("search")
                .at(at)
                .payload("q", phrase)
                .writeRef();
        fx.toolCall(pid, tool, "search", errorType, at);
        fx.rollup(pid, tool.traceId());
        return tool;
    }

    private void awaitChecks(String pid, int expected) {
        for (int i = 0; i < 100; i++) {
            if (checks.listByProject(pid).size() >= expected) return;
            sleep(100);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
