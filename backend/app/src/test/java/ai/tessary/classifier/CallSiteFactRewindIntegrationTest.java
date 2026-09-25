// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.worker.ClassifierJobRepository;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.model.CallSite;
import ai.tessary.model.Pipeline;
import ai.tessary.model.ProductProfile;
import ai.tessary.pipeline.PipelineService;
import ai.tessary.plan.Capability;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The chicken-and-egg this feature exists to break, end to end against the real pgvector Postgres.
 *
 * <p>The platform must ingest correctly-tagged traces before the plugin will assess the repo, and it
 * is that assessment's bundle which declares {@code call_site.output_schema}. So a project's first
 * traffic is always swept by Malformed Output before it has anything to gate on: it abstains, and the
 * cursor advances over the abstention.
 *
 * <p>These tests pin the fix at its real seams: a fact landing rewinds exactly the signals that
 * declare it, an unchanged re-declaration rewinds nothing, and a fact change never disturbs a signal
 * that reads only the trace.
 *
 * <p>{@link ai.tessary.model.CallSite} facts have one live reader in this build: {@code
 * MalformedOutputDetector} declares {@code OUTPUT_SCHEMA}. {@code
 * ClassifierService.rewindForCallSiteFact} asks {@code catalog.detectorFor(row.detector())} what
 * facts a signal reads and skips it when that answer is {@code null}, so a {@code SHAPE} fact
 * change rewinds nothing here: nothing on this classpath reads it.
 */
@SpringBootTest
class CallSiteFactRewindIntegrationTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}";

    private static final String SWEPT_AT = "2026-01-01T00:00:00Z";
    private static final String SWEPT_ID = "obs-before-synthesis";

    @Autowired
    ClassifierService signals;

    @Autowired
    ClassifierJobRepository jobs;

    @Autowired
    PipelineService pipelineService;

    @Autowired
    TenantService tenants;

    @Autowired
    CapabilityFixture capabilities;

    /**
     * A project whose built-ins have each already swept its pre-synthesis history — the state every
     * real project reaches before any repo assessment is possible.
     *
     * <p>Frustration is granted before the project exists, the moment seeding reads capabilities, so
     * its sweep job is a live control here for "a call-site fact does not disturb a signal that reads
     * only the trace."
     */
    private String projectWithSweptHistory(String name) {
        String pid = TenantFixture.bootstrap(tenants, name, org -> {
                    capabilities.grant(org.id(), Capability.FRUSTRATION);
                })
                .project()
                .id();
        signals.seedBuiltIns(pid); // idempotent; explicit so the test does not lean on listener timing
        for (ClassifierRow row : signals.list(pid)) {
            jobs.enqueue(pid, row.id(), 3600);
        }
        for (ClassifierJobRow job : jobs.claimBatch("setup-" + name, 500, 300, 5)) {
            if (job.projectId().equals(pid)) {
                jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);
            }
        }
        return pid;
    }

    private ClassifierJobRow jobFor(String projectId, String classifierKey) {
        ClassifierRow signal = signals.list(projectId).stream()
                .filter(s -> s.classifierKey().equals(classifierKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("built-in not seeded: " + classifierKey));
        ClassifierJobRow job = jobs.listByProject(projectId).stream()
                .filter(j -> j.classifierId().equals(signal.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no sweep job for: " + classifierKey));
        assertNotNull(job.status());
        return job;
    }

    private boolean rewound(String projectId, String classifierKey) {
        return jobFor(projectId, classifierKey).cursorId() == null;
    }

    @Test
    void declaringASchemaReopensTheHistorySweptBeforeItExisted() {
        String pid = projectWithSweptHistory("fact-rewind-schema");
        assertEquals(SWEPT_ID, jobFor(pid, "malformed_output").cursorId(), "setup: history already swept");

        // The bundle finally declares the call site's structured output.
        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", SCHEMA));

        assertTrue(rewound(pid, "malformed_output"), "the schema-gated built-in re-reads its whole history");
        assertEquals(
                ClassifierJobRow.PENDING,
                jobFor(pid, "malformed_output").status(),
                "and is due immediately rather than waiting on a heartbeat");
    }

    @Test
    void aFactOnlyRewindsTheSignalsThatDeclareIt() {
        String pid = projectWithSweptHistory("fact-rewind-isolation");

        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", SCHEMA));

        assertTrue(rewound(pid, "malformed_output"), "declares OUTPUT_SCHEMA");
        // Frustration reads the user's own words, so nothing declared by the repo can invalidate it.
        assertFalse(rewound(pid, "frustration"), "reads the trace alone");
        assertFalse(rewound(pid, "secret_leak"), "reads the trace alone");
    }

    @Test
    void clearingAStaleSchemaAlsoRewinds() {
        String pid = projectWithSweptHistory("fact-rewind-clear");
        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", SCHEMA));
        ClassifierJobRow job = jobFor(pid, "malformed_output");
        jobs.claimBatch("resweep-clear", 500, 300, 5);
        jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);

        // The code stopped declaring structured output. History scored against the withdrawn schema is
        // as wrong as history scored against no schema — both need re-reading.
        pipelineService.replace(pid, pipelineWithNullSchema("cs-answer", "rag_answer"));

        assertTrue(rewound(pid, "malformed_output"), "the withdrawn schema's verdicts are re-derived");
    }

    @Test
    void rewindSurvivesASignalThatHasNeverSwept() {
        // A project that declared a schema before any sweep job existed must not blow up; its first
        // sweep already starts from a null cursor, so there is nothing to rewind.
        String pid =
                TenantFixture.bootstrap(tenants, "fact-rewind-nojob").project().id();
        signals.seedBuiltIns(pid);

        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", SCHEMA));

        assertTrue(
                jobs.listByProject(pid).stream().allMatch(j -> j.cursorId() == null),
                "no job has a high-water mark to lose");
    }

    @Test
    void rewindReachesEveryCallSiteBecauseTheCursorIsPerSignal() {
        // The cursor is one (project, signal) high-water mark, so a fact landing on ONE call site
        // re-opens the signal's whole history — deliberately over-scanning rather than carrying a
        // per-call-site cursor. Safe because the worker's verdict write is idempotent.
        String pid = projectWithSweptHistory("fact-rewind-whole-signal");
        pipelineService.ensureCallSite(pid, "cs-one");

        pipelineService.upsert(pid, pipelineWithCallSite("cs-two", "rag_answer", SCHEMA));

        assertNull(
                jobFor(pid, "malformed_output").cursorAt(),
                "the signal re-reads observations of cs-one too — there is no per-call-site cursor to be precise with");
    }

    @Test
    void aDisabledSignalIsRewoundToo() {
        // Re-enabling a signal does NOT reset its cursor (enqueue re-pends "without disturbing its
        // cursor"), so skipping a disabled signal here would leave it resuming from its old high-water
        // mark — re-stranding exactly the history this feature recovers.
        String pid = projectWithSweptHistory("fact-rewind-disabled");
        ClassifierRow malformed = signals.list(pid).stream()
                .filter(s -> s.classifierKey().equals("malformed_output"))
                .findFirst()
                .orElseThrow();
        signals.setEnabled(pid, malformed.id(), false);

        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", SCHEMA));

        signals.setEnabled(pid, malformed.id(), true);
        assertTrue(rewound(pid, "malformed_output"), "the history is waiting for it when it comes back on");
    }

    @Test
    void aKeyReorderedSchemaIsNotAChange() {
        // A producer emits the keys in whatever order its run produced. Compared raw against a text
        // column, an identical schema would read as a change and rewind the whole history on every
        // import.
        String pid = projectWithSweptHistory("fact-rewind-key-order");
        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", SCHEMA));
        ClassifierJobRow job = jobFor(pid, "malformed_output");
        jobs.claimBatch("resweep-key-order", 500, 300, 5);
        jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);

        String reordered = "{\"required\":[\"answer\"],\"properties\":{\"answer\":{\"type\":\"string\"}},"
                + "\"type\":\"object\"}";
        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", reordered));

        assertFalse(rewound(pid, "malformed_output"), "the same schema with its keys reordered re-scores nothing");
    }

    @Test
    void aBundleDeclaringTheSameSchemaTwiceRewindsOnce() {
        String pid = projectWithSweptHistory("fact-rewind-bundle-idempotent");
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));
        ClassifierJobRow job = jobFor(pid, "malformed_output");
        jobs.claimBatch("resweep-bundle", 500, 300, 5);
        jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);

        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));

        assertFalse(rewound(pid, "malformed_output"), "re-importing an unchanged bundle re-scores nothing");
    }

    @Test
    void theDefaultUpsertImportPathRewindsToo() {
        // ImportController's DEFAULT mode is upsert, not replace — so every fact-change assertion
        // proven only against replace() is proven against the path most imports don't take.
        String pid = projectWithSweptHistory("fact-upsert-path");

        pipelineService.upsert(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));

        assertTrue(rewound(pid, "malformed_output"), "a schema arriving via upsert re-opens the history");
    }

    @Test
    void anExplicitNullInTheBundleClearsTheStoredSchema() {
        // The contract's two nulls, end to end. A shard SILENT on output_schema keeps the stored
        // schema; a shard declaring `output_schema: null` asserts the code has no structured output
        // and clears it. Jackson binds that null to NullNode rather than Java null, so without the
        // isNull() branch the column would hold the string "null" — not SQL NULL — and the Malformed
        // Output built-in would keep reading it back as a schema forever.
        String pid = projectWithSweptHistory("fact-bundle-explicit-null");
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));
        assertNotNull(storedSchema(pid, "cs-rag"), "setup: the bundle's schema is stored");

        pipelineService.replace(pid, pipelineWithNullSchema("cs-rag", "rag_answer"));

        assertNull(storedSchema(pid, "cs-rag"), "the explicit null cleared the stored schema");
    }

    @Test
    void aSilentShardKeepsTheStoredSchemaThatAnExplicitNullWouldClear() {
        String pid = projectWithSweptHistory("fact-bundle-silent-vs-null");
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));

        // Silent on the fact — the stored schema must survive the wipe-and-write.
        pipelineService.replace(pid, pipelineWithCallSiteShape("cs-rag", "rag_answer"));

        assertEquals(json(SCHEMA), storedSchema(pid, "cs-rag"), "silence carried the schema across");
    }

    @Test
    void aBundleDeclaredSchemaOverridesTheCarriedSchema() {
        // When the bundle declares a schema it must WIN over the carried-across one — otherwise a user
        // correcting a stale schema by editing the bundle would be silently overwritten by the very
        // mechanism that protects a silent shard.
        String pid = projectWithSweptHistory("fact-rewind-bundle-wins");
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));

        String corrected = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"number\"}}}";
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", corrected));

        assertEquals(json(corrected), storedSchema(pid, "cs-rag"), "the bundle's declaration is what is stored");
    }

    private @Nullable JsonNode storedSchema(String projectId, String callSiteId) {
        return pipelineService.getPipeline(projectId).callSites().stream()
                .filter(cs -> cs.id().equals(callSiteId))
                .findFirst()
                .orElseThrow()
                .outputSchema();
    }

    private static JsonNode json(String text) {
        try {
            return new ObjectMapper().readTree(text);
        } catch (JacksonException e) {
            throw new AssertionError("test fixture schema is not JSON", e);
        }
    }

    /** A minimal meta-bearing pipeline carrying exactly one call site with the given shape. */
    private static Pipeline pipelineWithCallSite(String callSiteId, String shape, String outputSchemaJson) {
        return withCallSite(callSiteId, shape, json(outputSchemaJson));
    }

    private static Pipeline pipelineWithCallSiteShape(String callSiteId, String shape) {
        return withCallSite(callSiteId, shape, null);
    }

    /** A shard that DECLARES `output_schema: null` — the code asserts it has no structured output. */
    private static Pipeline pipelineWithNullSchema(String callSiteId, String shape) {
        return withCallSite(callSiteId, shape, NullNode.getInstance());
    }

    private static Pipeline withCallSite(String callSiteId, String shape, @Nullable JsonNode outputSchema) {
        return new Pipeline(
                "0.3.0",
                "v1",
                List.of(),
                new ProductProfile("docs", List.of(), null, List.of(), List.of(), List.of(), List.of()),
                List.of(),
                List.of(),
                null,
                List.of(new CallSite(
                        callSiteId,
                        "answer_question",
                        "cli_agent",
                        "openai",
                        "gpt-4",
                        "you answer from the retrieved docs",
                        "the literal prompt template",
                        "def answer():\n    ...",
                        "src/app/answer.py",
                        7,
                        shape,
                        "high",
                        "answer from the docs",
                        List.of(),
                        3,
                        List.of(),
                        null,
                        null,
                        List.of(),
                        outputSchema,
                        List.of())),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }

    @Test
    void unrelatedFactsDoNotCollideAcrossProjects() {
        String pid = projectWithSweptHistory("fact-rewind-scope-a");
        String otherPid = projectWithSweptHistory("fact-rewind-scope-b");

        pipelineService.replace(pid, pipelineWithCallSite("cs-answer", "rag_answer", SCHEMA));

        assertTrue(rewound(pid, "malformed_output"));
        assertFalse(rewound(otherPid, "malformed_output"), "another tenant's sweep is untouched");
    }
}
