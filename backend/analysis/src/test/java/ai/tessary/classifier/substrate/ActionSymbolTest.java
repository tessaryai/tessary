// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The alphabet keeps a kind's name, normalized, and buckets a retrieval name to its corpus. */
class ActionSymbolTest {

    /**
     * One tool is one symbol however its calls are named: case, separators, a per-call id or uuid and edge
     * underscores all fold away, and a name that folds to nothing is {@code unnamed} rather than an empty
     * symbol. A tool split into one symbol per call would never gather enough calls to judge.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            nullValues = "NONE",
            value = {
                "TOOL      | __Search--Docs__                                  | tool:search_docs",
                "tool      | fetch_order-12345                                 | tool:fetch_order",
                "tool      | lookup 3f2504e0-4f89-11d3-9a0c-0305e82c3301       | tool:lookup",
                "tool      | ___                                               | tool:unnamed",
                "tool      | NONE                                              | tool:unnamed",
                "retrieval | kb#section-4                                      | retrieval:kb",
                "embedding | NONE                                              | embedding:unnamed",
                "NONE      | NONE                                              | :unnamed",
                "tool      | verify_member                                     | tool:verify_member",
                "agent     | policy-gpt                                        | agent:policy_gpt",
                "retrieval | policy_docs/2024/s4.pdf                           | retrieval:policy_docs"
            })
    void aNameFoldsToOneSymbolPerAction(@Nullable String kind, @Nullable String name, String symbol) {
        assertEquals(symbol, ActionSymbol.of(kind, name));
    }
}
