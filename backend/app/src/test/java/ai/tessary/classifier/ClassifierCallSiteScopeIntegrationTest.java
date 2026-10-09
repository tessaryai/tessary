// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.storage.SessionRepository;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanRepository;
import ai.tessary.storage.TraceV2Repository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.ClassifierRows;
import ai.tessary.testsupport.SubstrateV2Fixtures;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A classifier limited to some call sites, against real Postgres: the list is stored and survives a catalog re-sync,
 * only call sites the project has are accepted, adding one rewinds only a classifier whose rewind is free, and the two
 * reads the scope stands on return what they say.
 */
@SpringBootTest
class ClassifierCallSiteScopeIntegrationTest {

    private static final String SWEPT_AT = "2026-01-01T00:00:00Z";
    private static final String SWEPT_ID = "trace-before:span-before";
    private static final Instant T0 = Instant.now().minus(2, ChronoUnit.HOURS);

    @Autowired
    ClassifierService service;

    @Autowired
    ClassifierRepository signals;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    SubstrateReadRepository substrate;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

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

    private SubstrateV2Fixtures fx;

    @BeforeEach
    void setUp() {
        fx = new SubstrateV2Fixtures(sessions, traces, spans, payloads, jdbc);
    }

    /** A dropped column in the insert or the mapper loses the scope, and the classifier runs everywhere again. */
    @Test
    void aClassifierRowRoundTripsItsCallSites() {
        String pid = project("scope-round-trip").id();
        ClassifierRow row = new ClassifierRow(
                Ids.ulid(),
                pid,
                "scoped-regex",
                "Scoped regex",
                "A user classifier limited to two call sites.",
                BuiltInDetector.Kind.REGEX,
                "{\"patterns\":[\"x\"]}",
                false,
                3,
                false,
                ClassifierRow.Mode.TRACKING,
                "2026-10-01T00:00:00Z",
                "2026-10-02T00:00:00Z",
                List.of("cs-a", "cs-b"));

        signals.insert(row);

        assertEquals(row, signals.findById(pid, row.id()).orElseThrow());
    }

    /** A version bump re-syncs a built-in's definition; writing the scope there would undo the tenant's choice. */
    @Test
    void aCatalogResyncKeepsTheCallSites() {
        Project project = project("scope-resync");
        declare(project.id(), "cs-a");
        ClassifierRow leak = byKey(project.id(), BuiltInDetector.Kind.SECRET_LEAK);
        service.setCallSiteIds(project.id(), leak.id(), List.of("cs-a"));
        jdbc.sql("UPDATE classifier SET version = 0 WHERE id = :id")
                .param("id", leak.id())
                .update();

        service.resyncBuiltIns(project);

        ClassifierRow after = byKey(project.id(), BuiltInDetector.Kind.SECRET_LEAK);
        assertEquals(List.of("cs-a"), after.callSiteIds());
        assertNotEquals(0, after.version(), "setup: the re-sync really ran");
    }

    /**
     * Adding a call site rewinds a classifier with no model behind it, so the added call site's history is checked
     * rather than read as clean. Groundedness would send its whole history to the model again, so it is not rewound,
     * and narrowing never rewinds.
     */
    @Test
    void addingACallSiteRewindsOnlyAClassifierWhoseRewindIsFree() {
        Project project = project("scope-widen");
        String pid = project.id();
        declare(pid, "cs-a");
        declare(pid, "cs-b");
        ClassifierRow leak = byKey(pid, BuiltInDetector.Kind.SECRET_LEAK);
        ClassifierRow grounded = byKey(pid, BuiltInDetector.Kind.GROUNDEDNESS);
        service.setCallSiteIds(pid, leak.id(), List.of("cs-a"));
        service.setCallSiteIds(pid, grounded.id(), List.of("cs-a"));
        sweptToTheHead(pid);

        service.setCallSiteIds(pid, leak.id(), List.of("cs-a", "cs-b"));
        service.setCallSiteIds(pid, grounded.id(), List.of("cs-a", "cs-b"));

        assertNull(cursorOf(pid, leak), "secret leak re-reads its history for cs-b");
        assertEquals(SWEPT_ID, cursorOf(pid, grounded), "groundedness checks cs-b from now on");

        sweptToTheHead(pid);
        service.setCallSiteIds(pid, leak.id(), List.of("cs-b"));
        assertEquals(SWEPT_ID, cursorOf(pid, leak), "dropping a call site has no history to recover");
    }

    /** A typo'd id would scope the classifier to a call site with no traffic, and it would go quiet, not fail. */
    @Test
    void aCallSiteTheProjectDoesNotHaveIsRefusedAndNothingIsWritten() {
        String pid = project("scope-unknown").id();
        declare(pid, "cs-a");
        ClassifierRow leak = byKey(pid, BuiltInDetector.Kind.SECRET_LEAK);

        TessaryException refused = assertThrows(
                TessaryException.class, () -> service.setCallSiteIds(pid, leak.id(), List.of("cs-a", "cs-typo")));

        assertEquals(ClassifierError.UNKNOWN_CALL_SITE, refused.error());
        assertNull(byKey(pid, BuiltInDetector.Kind.SECRET_LEAK).callSiteIds());
    }

    /** Tool error does not read the scope yet, so accepting one would store a list that changes nothing. */
    @Test
    void toolErrorRefusesACallSiteScope() {
        String pid = project("scope-tool-error").id();
        declare(pid, "cs-a");
        ClassifierRow toolError = byKey(pid, BuiltInDetector.Kind.TOOL_ERROR);

        TessaryException refused = assertThrows(
                TessaryException.class, () -> service.setCallSiteIds(pid, toolError.id(), List.of("cs-a")));

        assertEquals(ClassifierError.CALL_SITE_SCOPE_UNSUPPORTED, refused.error());
    }

    /**
     * Frustration takes the same list as every other classifier, so it runs on every call site until one is set.
     * Widening it never rewinds: a rewind would send the whole history to the model again.
     */
    @Test
    void frustrationTakesTheListAndWideningItDoesNotRewind() {
        String pid = project("scope-frustration").id();
        declare(pid, "cs-a");
        declare(pid, "cs-b");
        ClassifierRow frustration = byKey(pid, BuiltInDetector.Kind.FRUSTRATION);
        assertNull(frustration.callSiteIds(), "a new Frustration classifier runs on every call site");

        service.setCallSiteIds(pid, frustration.id(), List.of("cs-a"));
        assertEquals(
                List.of("cs-a"), byKey(pid, BuiltInDetector.Kind.FRUSTRATION).callSiteIds());
        sweptToTheHead(pid);

        service.setCallSiteIds(pid, frustration.id(), null);

        assertNull(byKey(pid, BuiltInDetector.Kind.FRUSTRATION).callSiteIds());
        assertEquals(SWEPT_ID, cursorOf(pid, frustration), "Frustration checks the added call sites from now on");
    }

    /**
     * The picker offers what the bundle declares and what traffic arrived on. Missing the traced half leaves a project
     * with no bundle nothing to pick; listing the untagged pile would offer a call site that is not one.
     */
    @Test
    void theKnownCallSitesAreTheDeclaredOnesAndTheTracedOnes() {
        String pid = project("scope-known").id();
        declare(pid, "cs-declared");
        settledTrace(pid, "cs-traced", T0);
        settledTrace(pid, null, T0.plusSeconds(1));

        assertEquals(List.of("cs-declared", "cs-traced"), service.knownCallSiteIds(pid));
    }

    /** A span with no call site is scoped by its trace's other spans; a trace with none is absent, not empty. */
    @Test
    void callSitesByTraceReadsEveryTaggedSpanOfEachTrace() {
        String pid = project("scope-by-trace").id();
        String tagged = SubstrateV2Fixtures.traceId();
        String untagged = SubstrateV2Fixtures.traceId();
        fx.trace(pid, tagged, T0);
        fx.trace(pid, untagged, T0);
        String root = fx.spanSeed(pid)
                .traceId(tagged)
                .callSiteId("cs-a")
                .at(T0)
                .write()
                .id();
        fx.spanSeed(pid).traceId(tagged).parentSpanId(root).kind("tool").at(T0).write();
        fx.spanSeed(pid)
                .traceId(tagged)
                .parentSpanId(root)
                .callSiteId("cs-b")
                .at(T0)
                .write();
        fx.spanSeed(pid).traceId(untagged).at(T0).write();

        assertEquals(Map.of(tagged, Set.of("cs-a", "cs-b")), substrate.callSitesByTrace(pid, Set.of(tagged, untagged)));
    }

    private Project project(String name) {
        Project project = TenantFixture.bootstrap(tenants, name, org -> {
                    capabilities.grant(org.id(), Capability.GROUNDEDNESS);
                    capabilities.grant(org.id(), Capability.FRUSTRATION);
                })
                .project();
        service.seedBuiltIns(project.id());
        return project;
    }

    private void declare(String projectId, String callSiteId) {
        jdbc.sql("INSERT INTO call_site (project_id, id) VALUES (:pid, :id)")
                .param("pid", projectId)
                .param("id", callSiteId)
                .update();
    }

    private void settledTrace(String projectId, @Nullable String callSiteId, Instant at) {
        String traceId = SubstrateV2Fixtures.traceId();
        fx.trace(projectId, traceId, at);
        fx.spanSeed(projectId)
                .traceId(traceId)
                .callSiteId(callSiteId)
                .at(at)
                .endedAt(at.plusMillis(200))
                .write();
        fx.rollup(projectId, traceId);
    }

    private ClassifierRow byKey(String projectId, String classifierKey) {
        return ClassifierRows.byKey(signals, projectId, classifierKey).orElseThrow();
    }

    private void sweptToTheHead(String projectId) {
        for (ClassifierRow row : service.list(projectId)) {
            jobs.enqueue(projectId, row.id(), 3600);
        }
        for (ClassifierJobRow job : jobs.claimBatch("scope-setup", 500, 300, 5)) {
            if (job.projectId().equals(projectId)) {
                jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);
            }
        }
    }

    private @Nullable String cursorOf(String projectId, ClassifierRow signal) {
        return jobs.listByProject(projectId).stream()
                .filter(j -> j.classifierId().equals(signal.id()))
                .findFirst()
                .orElseThrow()
                .cursorId();
    }
}
