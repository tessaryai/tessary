// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.classifier.worker.ClassifierJobRepository;
import ai.tessary.evals.classifier.worker.ClassifierJobRow;
import ai.tessary.evals.model.CallSite;
import ai.tessary.evals.model.Pipeline;
import ai.tessary.evals.model.ProductProfile;
import ai.tessary.evals.pipeline.PipelineService;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.tenant.TenantService;
import ai.tessary.evals.testsupport.CapabilityFixture;
import ai.tessary.evals.testsupport.TenantFixture;
import ai.tessary.evals.testsupport.TurnGrainTestDetectionConfig;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The chicken-and-egg this feature exists to break, end to end against the real pgvector Postgres.
 *
 * <p>The platform must ingest correctly-tagged traces before the plugin will assess the repo, and it
 * is that repo assessment (agentic synthesis) which captures {@code call_site.output_schema} and
 * {@code call_site.shape}. So a project's first traffic is ALWAYS swept by Malformed Output and
 * Groundedness before either has anything to gate on: they abstain, the cursor advances over the
 * abstention, and — before this — that history was unscoreable forever while the product rendered it
 * as "nothing found".
 *
 * <p>These tests pin the fix at its real seams: a fact landing rewinds exactly the signals that
 * declare it, an unchanged re-capture rewinds nothing, and a fact change never disturbs a signal that
 * reads only the trace.
 *
 * <h2>The SHAPE half of this file left with #888, and is owed back</h2>
 *
 * <p>{@link ai.tessary.evals.model.CallSite} facts have exactly two readers. {@code
 * MalformedOutputDetector} declares {@code OUTPUT_SCHEMA} and stays open; {@code GroundednessDetector}
 * declared {@code SHAPE} and moved to {@code tessary-paid/groundedness} with #888. {@code
 * ClassifierService.rewindForCallSiteFact} asks {@code catalog.detectorFor(row.detector())} what facts a
 * signal reads and skips it when that answer is {@code null} — so in an open build a shape change now
 * rewinds nothing, because the only signal that ever cared is not on the classpath.
 *
 * <p>Three tests asserting the shape rewind were therefore DELETED rather than relocated, following the
 * precedent set for behaviour drift and the paid plan module: {@code @SpringBootTest} lives in {@code
 * backend/app}, whose dependency closure can never contain a paid module, and the overlay has no
 * integration-test harness. Epic 5 owns building that harness. Three more assertions went with them —
 * {@code assertFalse(rewound(pid, "groundedness"), ...)} in three surviving tests — NOT because they
 * failed, but because they would have kept passing on the detector's absence instead of on the rule they
 * name. A green assertion that cannot fail is worse than a missing one.
 *
 * <p>What is gone, precisely, so it can be restored rather than reinvented: a shape import re-opens
 * groundedness's history; re-importing the same shape rewinds nothing (the diff is taken before
 * {@code replace()} wipes the call sites); a shape moving from outside {@code GROUNDED_SHAPES} to inside
 * it re-opens history; and the default {@code upsert(pipeline, true)} import path rewinds on shape the
 * same way it does on schema.
 */
@SpringBootTest
@Import(TurnGrainTestDetectionConfig.class)
class CallSiteFactRewindIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

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
     * <p>Frustration is a paid classifier and OFF by default in an open build (#887/#888), but its
     * detector is the always-open {@code EncoderDetector}, so it is still a live control here for "a
     * call-site fact does not disturb a signal that reads only the trace" — granted before the project
     * exists, the moment seeding reads capabilities. Groundedness is NOT granted: a grant would seed the
     * row but its detector left with #888, so every groundedness assertion here would pass on the
     * detector's absence rather than on the rewind rule. See the class note above.
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
    void capturingASchemaReopensTheHistorySweptBeforeItExisted() {
        String pid = projectWithSweptHistory("fact-rewind-schema");
        assertEquals(SWEPT_ID, jobFor(pid, "malformed_output").cursorId(), "setup: history already swept");
        pipelineService.ensureCallSite(pid, "cs-answer");

        // Synthesis finally runs and reads the call site's declared structured output.
        assertTrue(
                pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA),
                "a first capture is a genuine change");

        assertTrue(rewound(pid, "malformed_output"), "the schema-gated built-in re-reads its whole history");
        assertEquals(
                ClassifierJobRow.PENDING,
                jobFor(pid, "malformed_output").status(),
                "and is due immediately rather than waiting on a heartbeat");
    }

    @Test
    void aFactOnlyRewindsTheSignalsThatDeclareIt() {
        String pid = projectWithSweptHistory("fact-rewind-isolation");
        pipelineService.ensureCallSite(pid, "cs-answer");

        pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA);

        assertTrue(rewound(pid, "malformed_output"), "declares OUTPUT_SCHEMA");
        // Frustration reads the user's own words, so nothing captured from the repo can invalidate it.
        assertFalse(rewound(pid, "frustration"), "reads the trace alone");
        assertFalse(rewound(pid, "secret_leak"), "reads the trace alone");
    }

    @Test
    void reCapturingTheSameSchemaRewindsNothing() {
        String pid = projectWithSweptHistory("fact-rewind-idempotent");
        pipelineService.ensureCallSite(pid, "cs-answer");
        pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA);

        // Re-sweep to a fresh high-water mark, as the rewound sweep would.
        ClassifierJobRow after = jobFor(pid, "malformed_output");
        jobs.claimBatch("resweep", 500, 300, 5);
        jobs.markSwept(after.id(), SWEPT_AT, SWEPT_ID);

        // Every generation run re-reads the same code. Re-scoring all history on each one would be
        // pure waste, so an unchanged capture must be silent.
        assertFalse(
                pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA),
                "re-writing an identical schema is not a change");
        assertFalse(rewound(pid, "malformed_output"), "an unchanged capture leaves the cursor where it was");
    }

    @Test
    void clearingAStaleSchemaAlsoRewinds() {
        String pid = projectWithSweptHistory("fact-rewind-clear");
        pipelineService.ensureCallSite(pid, "cs-answer");
        pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA);
        ClassifierJobRow job = jobFor(pid, "malformed_output");
        jobs.claimBatch("resweep-clear", 500, 300, 5);
        jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);

        // The code stopped declaring structured output. History scored against the withdrawn schema is
        // as wrong as history scored against no schema — both need re-reading.
        assertTrue(
                pipelineService.setCallSiteOutputSchema(pid, "cs-answer", null),
                "clearing a stale capture is a change");
        assertTrue(rewound(pid, "malformed_output"), "the withdrawn schema's verdicts are re-derived");
    }

    @Test
    void rewindSurvivesASignalThatHasNeverSwept() {
        // A project that captured a schema before any sweep job existed must not blow up; its first
        // sweep already starts from a null cursor, so there is nothing to rewind.
        String pid =
                TenantFixture.bootstrap(tenants, "fact-rewind-nojob").project().id();
        signals.seedBuiltIns(pid);
        pipelineService.ensureCallSite(pid, "cs-answer");

        assertTrue(pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA));

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
        pipelineService.ensureCallSite(pid, "cs-two");

        pipelineService.setCallSiteOutputSchema(pid, "cs-two", SCHEMA);

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
        pipelineService.ensureCallSite(pid, "cs-answer");

        pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA);

        signals.setEnabled(pid, malformed.id(), true);
        assertTrue(rewound(pid, "malformed_output"), "the history is waiting for it when it comes back on");
    }

    @Test
    void aKeyReorderedSchemaIsNotAChange() {
        // The agent re-reads the same code on every generation run and emits the keys in whatever order
        // that run produced. Compared raw against a text column, an identical schema would read as a
        // change and rewind the whole history on every single run.
        String pid = projectWithSweptHistory("fact-rewind-key-order");
        pipelineService.ensureCallSite(pid, "cs-answer");
        pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA);
        ClassifierJobRow job = jobFor(pid, "malformed_output");
        jobs.claimBatch("resweep-key-order", 500, 300, 5);
        jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);

        String reordered = "{\"required\":[\"answer\"],\"properties\":{\"answer\":{\"type\":\"string\"}},"
                + "\"type\":\"object\"}";
        assertFalse(
                pipelineService.setCallSiteOutputSchema(pid, "cs-answer", reordered),
                "the same schema with its keys in a different order is the same schema");
        assertFalse(rewound(pid, "malformed_output"), "so nothing is re-scored");
    }

    @Test
    void aBundleImportDoesNotDropACapturedSchema() {
        // output_schema is platform-captured, not bundle content, and replace() wipes call sites before
        // re-inserting them. Losing it would silently un-arm the Malformed Output built-in AND make the
        // next synth capture read as a first capture, rewinding all history on every import.
        String pid = projectWithSweptHistory("fact-rewind-import-preserves");
        pipelineService.replace(pid, pipelineWithCallSiteShape("cs-rag", "rag_answer"));
        pipelineService.setCallSiteOutputSchema(pid, "cs-rag", SCHEMA);
        ClassifierJobRow job = jobFor(pid, "malformed_output");
        jobs.claimBatch("resweep-import", 500, 300, 5);
        jobs.markSwept(job.id(), SWEPT_AT, SWEPT_ID);

        pipelineService.replace(pid, pipelineWithCallSiteShape("cs-rag", "rag_answer"));

        assertFalse(
                pipelineService.setCallSiteOutputSchema(pid, "cs-rag", SCHEMA),
                "the import carried the captured schema across the wipe, so re-capturing it is not a change");
        assertFalse(rewound(pid, "malformed_output"), "and no history is needlessly re-scored");
    }

    @Test
    void aSchemaDeclaredByTheBundleRewindsLikeAnyOtherCapture() {
        // The bundle is the second writer of output_schema. A schema arriving via import must re-open
        // the same stranded history a synthesis capture does — the classifier cannot tell, or care,
        // which writer produced it.
        String pid = projectWithSweptHistory("fact-rewind-bundle-schema");

        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));

        assertTrue(rewound(pid, "malformed_output"), "a bundle-declared schema re-opens the history");
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
        // ImportController's DEFAULT mode is upsert(pipeline, true), not replace — so every fact-change
        // assertion proven only against replace() is proven against the path most imports don't take.
        String pid = projectWithSweptHistory("fact-upsert-path");

        pipelineService.upsert(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA), true);

        assertTrue(rewound(pid, "malformed_output"), "a schema arriving via upsert re-opens the history");
    }

    @Test
    void aGradersOnlyUpsertRewindsNothing() {
        // replaceMeta=false never touches call_site, so it cannot move a fact — and must not pay for a
        // fact diff or trigger a re-sweep.
        String pid = projectWithSweptHistory("fact-upsert-graders-only");
        pipelineService.upsert(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA), false);

        assertFalse(rewound(pid, "malformed_output"), "a graders-only upload moves no call-site fact");
    }

    @Test
    void anExplicitNullInTheBundleClearsTheCapturedSchema() {
        // The contract's two nulls, end to end. A shard SILENT on output_schema keeps the platform's
        // capture; a shard declaring `output_schema: null` asserts the code has no structured output
        // and clears it. Jackson binds that null to NullNode rather than Java null, so without the
        // isNull() branch the column would hold the string "null" — not SQL NULL — and the Malformed
        // Output built-in would keep reading it back as a schema forever.
        String pid = projectWithSweptHistory("fact-bundle-explicit-null");
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));
        assertFalse(
                pipelineService.setCallSiteOutputSchema(pid, "cs-rag", SCHEMA), "setup: the bundle's schema is stored");

        pipelineService.replace(pid, pipelineWithNullSchema("cs-rag", "rag_answer"));

        assertTrue(
                pipelineService.setCallSiteOutputSchema(pid, "cs-rag", SCHEMA),
                "the capture was cleared, so re-capturing the schema is a genuine change again");
    }

    @Test
    void aSilentShardKeepsTheCapturedSchemaThatAnExplicitNullWouldClear() {
        String pid = projectWithSweptHistory("fact-bundle-silent-vs-null");
        pipelineService.ensureCallSite(pid, "cs-rag");
        pipelineService.setCallSiteOutputSchema(pid, "cs-rag", SCHEMA);

        // Silent on the fact — the platform's capture must survive the wipe-and-write.
        pipelineService.replace(pid, pipelineWithCallSiteShape("cs-rag", "rag_answer"));

        assertFalse(
                pipelineService.setCallSiteOutputSchema(pid, "cs-rag", SCHEMA),
                "silence carried the capture across, so re-capturing it is not a change");
    }

    @Test
    void aBundleDeclaredSchemaOverridesTheCarriedCapture() {
        // The repo is the source of truth. When the bundle declares a schema it must WIN over the
        // platform's carried-across capture — otherwise a user correcting a stale capture by editing
        // the bundle would be silently overwritten by the very mechanism that protects them.
        String pid = projectWithSweptHistory("fact-rewind-bundle-wins");
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", SCHEMA));
        pipelineService.setCallSiteOutputSchema(pid, "cs-rag", "{\"type\":\"object\"}");

        String corrected = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"number\"}}}";
        pipelineService.replace(pid, pipelineWithCallSite("cs-rag", "rag_answer", corrected));

        assertFalse(
                pipelineService.setCallSiteOutputSchema(pid, "cs-rag", corrected),
                "the bundle's declaration is what is stored, so re-capturing it is not a change");
    }

    /** A minimal meta-bearing pipeline carrying exactly one call site with the given shape. */
    private static Pipeline pipelineWithCallSite(String callSiteId, String shape, String outputSchemaJson) {
        try {
            return withCallSite(callSiteId, shape, new ObjectMapper().readTree(outputSchemaJson));
        } catch (JacksonException e) {
            throw new AssertionError("test fixture schema is not JSON", e);
        }
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
        pipelineService.ensureCallSite(pid, "cs-answer");

        pipelineService.setCallSiteOutputSchema(pid, "cs-answer", SCHEMA);

        assertTrue(rewound(pid, "malformed_output"));
        assertFalse(rewound(otherPid, "malformed_output"), "another tenant's sweep is untouched");
    }
}
