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
 * The production-signal to pre-deploy loop against real Postgres: with it enabled, a new signal found by the {@link
 * ClassifierWorker} sweep registers a {@code pre_deploy_check} per surface its {@code config_json.surfaces} names,
 * idempotently.
 */
@SpringBootTest
class PreDeploySignalLoopIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("tessary.predeploy.enabled", () -> "true"); // activate the loop
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

        // Implicates two surfaces. Regex, the detector user-authored signals run on.
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

        SpanRef tool = seedFailingToolSpan(pid, "upstream exploded", "HTTP 500 upstream");

        signalService.seedBuiltIns(pid); // idempotent
        worker.tick();
        awaitChecks(pid, 2);

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

        // A second tick registers nothing new.
        signalService.seedBuiltIns(pid); // idempotent
        worker.tick();
        sleep(1_000);
        assertEquals(2, checks.listByProject(pid).size(), "re-discovery of the same signal is a no-op");

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

        // No explicit surfaces and no learned failure mode: a no-op.
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

        signalService.seedBuiltIns(pid); // idempotent
        worker.tick();
        sleep(1_500); // nothing should register

        assertTrue(
                checks.listByProject(pid).isEmpty(),
                "a signal with no surface mapping registers no check — never a fabricated surface");
    }

    /**
     * Only real surface names register (a typo is dropped, not invented), severity sets intensity, a repeat registers
     * nothing, an unreadable config registers nothing, and dismiss/reinstate round-trips.
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
     * A settled turn whose {@code tool} span output carries {@code phrase}, with its {@code tool_call} row.
     *
     * @return the span's producer identity
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

    /** The checks endpoints serve org members only while the org holds the CI-integration entitlement. */
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
