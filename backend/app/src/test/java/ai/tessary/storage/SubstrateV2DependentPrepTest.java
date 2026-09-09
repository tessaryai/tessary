// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The dependent side of the substrate: producer-key columns on the tables that point at spans, and the
 * additively widened subject/grain vocabulary the v2 writers use.
 *
 * <p><b>Post-teardown, this asserts the destination rather than the preparation.</b> 0077 added the
 * producer keys beside the surrogates under {@code v2_*} names, because both had to be readable at once
 * while the sweep translated between them. 0083 finished the swap: the surrogates are gone and the
 * producer keys carry the canonical names. What is asserted here is that end state.
 *
 * <p>{@code dataset_item} was a third table in both lists, and its {@code source_session_id} the one
 * deliberate surviving pointer. Track A dropped the table, so neither has anything left to assert.
 *
 * <p><b>Why the widening is asserted as constraint text.</b> The vocabulary widening is a property of the
 * constraint, not of any row: a CHECK that still rejects {@code 'span'} is broken whether or not a test
 * happens to insert one. {@code attribute_key} gets the real insert because it is cheap there.
 */
@SpringBootTest
class SubstrateV2DependentPrepTest {

    @Autowired
    JdbcClient jdbc;

    // ---- the v1 substrate itself -------------------------------------------------------------------

    @ParameterizedTest(name = "{0} no longer exists")
    @ValueSource(strings = {"message_block", "message", "feedback", "observation", "context", "trace_v2"})
    void theV1SubstrateAndItsSuccessorsWorkingNameAreGone(String table) {
        assertEquals(
                0,
                relations(table),
                table + " was dropped or renamed by 0083. A surviving one means the migration ran against a"
                        + " schema it did not expect, and every reader below is then asserting the wrong thing");
    }

    @Test
    @DisplayName("`trace` is the v2 table now, not a v1 leftover that happened to keep the name")
    void theTraceNameBelongsToV2() {
        assertEquals(1, relations("trace"), "the name is taken, and taken by the v2 table");
        for (String column : List.of("rollup_due_at", "is_settled", "span_count", "input_preview", "call_site_id")) {
            assertEquals(
                    1,
                    columns("trace", column),
                    "trace." + column + " exists only on the v2 table — checking a rollup column is how this"
                            + " distinguishes the rename having happened from the v1 table having survived it");
        }
    }

    @ParameterizedTest(name = "{0} went with the job that owned it")
    @ValueSource(strings = {"substrate_v2_id_map", "substrate_v2_backfill_cursor"})
    void theBackfillMachineryIsGone(String table) {
        assertEquals(
                0,
                relations(table),
                table + " outlived its purpose at the teardown: the sweep converged and the deep-link shim"
                        + " that read the map is retired in the same release");
    }

    @ParameterizedTest(name = "{0} is deliberately kept")
    @ValueSource(strings = {"synth_trace_id", "synth_span_id"})
    void theIdSynthesisRecipeSurvives(String function) {
        assertEquals(
                1,
                jdbc.sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                                + " WHERE n.nspname = 'public' AND p.proname = :n")
                        .param("n", function)
                        .query(Integer.class)
                        .single(),
                function + " is the spec's deterministic id recipe expressed once, in the database. Any future"
                        + " ingest path for a producer that sends no trace id needs exactly it, and re-deriving"
                        + " it later from prose is how two implementations of it end up disagreeing. 0095 dropped"
                        + " the substrate_v2_ prefix: a permanent platform function named after the temporary"
                        + " migration that introduced it sends the next reader looking for a migration");
    }

    // ---- producer-key columns ----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}.{1} carries the producer key under its canonical name")
    @CsvSource({
        "tool_call,trace_id",
        "tool_call,span_id",
        "retrieved_doc,trace_id",
        "retrieved_doc,span_id",
    })
    void producerKeyColumnsExist(String table, String column) {
        assertEquals(
                1,
                jdbc.sql("SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'public' AND table_name = :t AND column_name = :c"
                                + " AND is_nullable = 'YES'")
                        .param("t", table)
                        .param("c", column)
                        .query(Integer.class)
                        .single(),
                table + "." + column + " must exist and be nullable — a row whose producer key the sweep"
                        + " could not resolve says so with a NULL rather than with an invented id");
    }

    @ParameterizedTest(name = "{0}.{1} was dropped with the v1 substrate")
    @CsvSource({
        "tool_call,observation_id",
        "tool_call,v2_trace_id",
        "retrieved_doc,observation_id",
        "retrieved_doc,v2_trace_id",
        "pre_deploy_check,feedback_observation_id",
    })
    void surrogatePointersAreGone(String table, String column) {
        assertEquals(
                0,
                columns(table, column),
                table + "." + column + " pointed at a table 0083 dropped; leaving the column would leave a"
                        + " dangling id nothing can resolve");
    }

    // ---- vocabulary widening -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0} names the v2 vocabulary and only the v2 vocabulary")
    // Four names left this list with Track A (0016): verdict, annotation, annotation_queue_item and
    // label were dropped outright, so their CHECKs cannot be inspected and their absence is asserted
    // by theV1SubstrateAndItsSuccessorsWorkingNameAreGone's sibling in check-migrations-populated.sh
    // rather than here. The one carrier that survives still has to name the v2 vocabulary
    // (embedding_subject_kind_check left with the embedding lane, #1116).
    @ValueSource(strings = {"failure_mode_instance_subject_kind_check"})
    void subjectKindVocabularyIsV2Only(String constraint) {
        String def = constraintDef(constraint);
        assertTrue(def.contains("'span'"), constraint + " must accept 'span': " + def);
        assertTrue(def.contains("'session'"), constraint + " must accept 'session': " + def);
        assertFalse(
                def.contains("'observation'") || def.contains("'context'"),
                "0094 migrated the v1 rows onto session/span and narrowed this CHECK; a legacy arm left"
                        + " standing would let a writer mint another row nothing resolves: " + def);
    }

    // A @ParameterizedTest over ck_verdict_grain / ck_annotation_grain / ck_annotation_queue_item_grain
    // / ck_label_grain used to sit here. All four went with their tables in 0016 (Track A), and the
    // claim they encoded — a span-grain subject must carry its trace id, because span identity is
    // (project_id, trace_id, id) — now has exactly one carrier left. The next test is its only guard.

    @Test
    @DisplayName("failure_mode_instance's span branch demands both of its subject columns")
    void fmiSpanBranchRequiresBoth() {
        String def = constraintDef("ck_fmi_subject").replaceAll("\\s+", " ");
        assertTrue(def.contains("subject_trace_id IS NOT NULL"));
        assertTrue(def.contains("subject_span_id IS NOT NULL"));
        assertTrue(def.contains("'span'"));
    }

    // ---- the v2 indexes ----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} exists")
    @ValueSource(
            strings = {
                "ix_span_project_started",
                "ix_span_path",
                "ix_span_call_site",
                "ix_span_unresolved_path",
                "ix_span_uncorrelated",
                "ix_trace_project_started",
                "ix_trace_session",
                "ix_trace_rollup_due",
                "ix_session_project_active",
                "ix_span_payload_fts",
            })
    void specIndexesExist(String index) {
        assertEquals(
                1,
                jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = :n")
                        .param("n", index)
                        .query(Integer.class)
                        .single());
    }

    @ParameterizedTest(name = "{0} carries its resolver terminal-state predicate")
    @CsvSource({
        "ix_span_unresolved_path,path_state",
        "ix_span_uncorrelated,correlation_state",
    })
    void resolverIndexesCarryTheTerminalStatePredicate(String index, String stateColumn) {
        String def = indexDef(index);
        assertTrue(
                def.contains(stateColumn + " = 'pending'"),
                "without the terminal state, a permanent resident — an orphan whose parent was never"
                        + " shipped, or anonymous session-less traffic — never leaves this index and"
                        + " eventually crowds real work out of every LIMITed batch: " + def);
    }

    @Test
    @DisplayName("the FTS expression is capped, and the cap is the exact one readers must repeat")
    void ftsExpressionIsCapped() {
        String def = indexDef("ix_span_payload_fts").replaceAll("\\s+", " ");
        assertTrue(def.contains("100000"), "an uncapped tsvector aborts the INSERT on a multi-megabyte prompt: " + def);
        assertTrue(def.contains("gin"), def);
        assertTrue(
                def.contains("'simple'"),
                "Postgres matches an expression index only against a syntactically identical expression,"
                        + " so GlobalSearchRepository must repeat this one character for character: " + def);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /**
     * Tables and views by name. {@code information_schema.tables} would answer for tables only, and a
     * dropped table replaced by a compat view is exactly the outcome this migration decided against — so
     * the probe has to see both kinds or it would call that case a pass.
     */
    private int relations(String name) {
        return jdbc.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'public' AND c.relname = :n AND c.relkind IN ('r', 'v', 'm', 'p')")
                .param("n", name)
                .query(Integer.class)
                .single();
    }

    private int columns(String table, String column) {
        return jdbc.sql("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = :t AND column_name = :c")
                .param("t", table)
                .param("c", column)
                .query(Integer.class)
                .single();
    }

    private String constraintDef(String name) {
        return jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = :n")
                .param("n", name)
                .query(String.class)
                .single();
    }

    private String indexDef(String name) {
        return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = :n")
                .param("n", name)
                .query(String.class)
                .single();
    }
}
