// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.worker.ClassifierWorker;
import ai.tessary.gate.PreDeployCheckDtos.PreDeployCheckView;
import ai.tessary.gate.PreDeployCheckRepository;
import ai.tessary.gate.PreDeployCheckRow;
import ai.tessary.gate.PreDeployCheckService;
import ai.tessary.gate.PreDeployCheckService.ClassifierDiscovery;
import ai.tessary.model.Severity;
import ai.tessary.model.TouchedSurface;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.PreDeployError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.SubstrateV2Fixtures.SpanRef;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    PreDeployCheckController controller;

    @Autowired
    CapabilityFixture capabilities;

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
        List<String> surfaces = rows.stream().map(PreDeployCheckRow::surface).toList();
        assertTrue(
                surfaces.contains(TouchedSurface.TOOL_DEFINITION.wire())
                        && surfaces.contains(TouchedSurface.AGENT_LOOP.wire()),
                "the implicated surfaces are registered: " + surfaces);

        // Idempotency: a second tick over the same substrate registers no new checks.
        signalService.seedBuiltIns(pid); // the generation-run trigger's effect (idempotent)
        worker.tick();
        sleep(1_000);
        assertEquals(2, checks.listByProject(pid).size(), "re-discovery of the same signal is a no-op");

        // The dismiss lifecycle retires one check without touching the signal.
        preDeployChecks.dismiss(pid, rows.get(0).id());
        assertEquals(
                1,
                checks.listByProject(pid).stream()
                        .filter(r -> PreDeployCheckRow.Status.ACTIVE.equals(r.status()))
                        .count(),
                "a dismissed check leaves the active set");
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
     * Registration straight through the service. Only real surface names register (a typo or a
     * non-string is dropped, never invented into a surface), the discovery's severity sets the check's
     * intensity, a second discovery registers nothing new, a config it cannot read registers nothing, and
     * the dismiss/reinstate lifecycle round-trips on the list read.
     */
    @Test
    void registrationKeepsOnlyRealSurfacesAtTheSeveritysIntensityAndTheLifecycleRoundTrips() {
        String pid =
                TenantFixture.bootstrap(tenants, "predeploy-direct").project().id();
        String now = Instant.now().toString();
        String classifierId = Ids.ulid();
        signals.insert(new ClassifierRow(
                classifierId,
                pid,
                "direct_probe",
                "Direct Probe",
                null,
                "regex",
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));
        var critical = ClassifierDiscovery.of(
                pid,
                classifierId,
                "direct_probe",
                "{\"surfaces\":[\"prompt\",7,\"not_a_surface\"]}",
                Severity.CRITICAL);

        assertEquals(1, preDeployChecks.registerForSignal(critical), "only the real surface registers");
        assertEquals(0, preDeployChecks.registerForSignal(critical), "a second discovery is a no-op");
        assertEquals(
                1,
                preDeployChecks.registerForSignal(ClassifierDiscovery.of(
                        pid, classifierId, "direct_probe", "{\"surfaces\":[\"dependency\"]}", Severity.INFO)));
        assertEquals(
                1,
                preDeployChecks.registerForSignal(ClassifierDiscovery.of(
                        pid, classifierId, "direct_probe", "{\"surfaces\":[\"model_params\"]}", null)));
        assertEquals(
                0,
                preDeployChecks.registerForSignal(
                        ClassifierDiscovery.of(pid, classifierId, "direct_probe", "{not json", Severity.CRITICAL)),
                "an unreadable config registers nothing rather than failing the sweep");

        Map<String, PreDeployCheckView> bySurface = new HashMap<>();
        preDeployChecks.list(pid).forEach(v -> bySurface.put(v.surface(), v));
        PreDeployCheckView prompt = bySurface.get("prompt");
        assertEquals(
                new PreDeployCheckView(
                        prompt.id(),
                        classifierId,
                        "prompt",
                        null,
                        "high",
                        "active",
                        prompt.createdAt(),
                        prompt.createdAt()),
                prompt);
        assertEquals("low", bySurface.get("dependency").intensity(), "info severity is a low-intensity check");
        assertEquals("medium", bySurface.get("model_params").intensity(), "no severity defaults to medium");

        preDeployChecks.dismiss(pid, prompt.id());
        assertEquals("dismissed", status(pid, prompt.id()));
        preDeployChecks.reinstate(pid, prompt.id());
        assertEquals("active", status(pid, prompt.id()));

        TessaryException missing =
                assertThrows(TessaryException.class, () -> preDeployChecks.reinstate(pid, "no-such-check"));
        assertEquals(PreDeployError.NOT_FOUND, missing.error());
    }

    private String status(String pid, String id) {
        return preDeployChecks.list(pid).stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
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

    /**
     * The checks endpoints list, dismiss and reinstate a project's checks for its org's members, and only while
     * the org holds the CI-integration entitlement: with it withheld, every endpoint is refused rather than
     * reading or changing a check the org is not entitled to.
     */
    @Test
    void theChecksEndpointsServeOnlyAnOrgHoldingTheCiIntegrationEntitlement() {
        var fix = TenantFixture.bootstrap(tenants, "predeploy-http");
        String pid = fix.project().id();
        String now = Instant.now().toString();
        String classifierId = Ids.ulid();
        signals.insert(new ClassifierRow(
                classifierId,
                pid,
                "http_probe",
                "HTTP Probe",
                null,
                "regex",
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                now,
                now));
        preDeployChecks.registerForSignal(ClassifierDiscovery.of(
                pid, classifierId, "http_probe", "{\"surfaces\":[\"prompt\"]}", Severity.CRITICAL));
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
        String org = fix.org().slug();
        String project = fix.project().slug();

        List<PreDeployCheckView> listed = controller.list(owner, org, project).data();
        assertEquals(
                List.of("prompt"),
                listed.stream().map(PreDeployCheckView::surface).toList());
        String id = listed.get(0).id();
        controller.dismiss(owner, org, project, id);
        assertEquals("dismissed", status(pid, id));
        controller.reinstate(owner, org, project, id);
        assertEquals("active", status(pid, id));

        capabilities.withhold(fix.org().id(), Capability.CI_INTEGRATION);
        TessaryException listing = assertThrows(TessaryException.class, () -> controller.list(owner, org, project));
        TessaryException dismissing =
                assertThrows(TessaryException.class, () -> controller.dismiss(owner, org, project, id));
        TessaryException reinstating =
                assertThrows(TessaryException.class, () -> controller.reinstate(owner, org, project, id));

        assertEquals(CapabilityError.DISABLED, listing.error());
        assertEquals(CapabilityError.DISABLED, dismissing.error());
        assertEquals(CapabilityError.DISABLED, reinstating.error());
        assertEquals("active", status(pid, id), "a refused dismiss must not have changed the check");
    }
}
