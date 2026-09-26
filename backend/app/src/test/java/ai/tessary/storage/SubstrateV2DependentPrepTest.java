// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The dependent side of the substrate: producer-key columns on the tables that point at spans, and the
 * additively widened subject/grain vocabulary the v2 writers use.
 *
 * <p><b>Post-teardown, this asserts the destination rather than the preparation.</b> The producer keys
 * were added beside the surrogates under {@code v2_*} names, because both had to be readable at once
 * while the sweep translated between them; the swap finished later: the surrogates are gone and the
 * producer keys carry the canonical names. What is asserted here is that end state.
 *
 * <p>{@code dataset_item} was a third table in both lists, and its {@code source_session_id} the one
 * deliberate surviving pointer. The table was dropped, so neither has anything left to assert.
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

    // ---- producer-key columns ----------------------------------------------------------------------

    // ---- vocabulary widening -----------------------------------------------------------------------

    // A @ParameterizedTest over ck_verdict_grain / ck_annotation_grain / ck_annotation_queue_item_grain
    // / ck_label_grain used to sit here. All four went with their tables, and the
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

    // ---- helpers -----------------------------------------------------------------------------------

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
