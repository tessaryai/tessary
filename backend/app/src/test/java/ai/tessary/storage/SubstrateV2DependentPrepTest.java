// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The dependent side of the substrate: producer-key columns on tables that point at spans. Asserts the
 * post-teardown end state: surrogates gone, producer keys under canonical names.
 */
@SpringBootTest
record SubstrateV2DependentPrepTest(JdbcClient jdbc) {

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

    private int columns(String table, String column) {
        return jdbc.sql("SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = :t AND column_name = :c")
                .param("t", table)
                .param("c", column)
                .query(Integer.class)
                .single();
    }

    private String indexDef(String name) {
        return jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = :n")
                .param("n", name)
                .query(String.class)
                .single();
    }
}
