// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.promptlib.PromptListFilter.ArrayMatch;
import ai.tessary.promptlib.PromptListFilter.ConfigMatch;
import ai.tessary.promptlib.PromptListFilter.NumberMatch;
import ai.tessary.promptlib.PromptListFilter.OptionsMatch;
import ai.tessary.promptlib.PromptListFilter.TextMatch;
import ai.tessary.promptlib.PromptListFilter.TimeMatch;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The JSON {@code filter} of the public list, in the grammar of another prompt tool: a list of conditions, each with a
 * {@code type}, a {@code column} that type allows, an {@code operator} and a value. The bugs: a condition on a column or
 * with an operator outside the grammar reaches SQL, a malformed value is a 500, and a script written for that tool gets
 * different rows.
 */
class PromptListFilterTest {

    @Test
    void parse_readsEveryConditionType() {
        String json = """
                [{"type":"string","column":"name","operator":"contains","value":"bot"},
                 {"type":"string","column":"id","operator":"is not empty"},
                 {"type":"stringOptions","column":"type","operator":"none of","value":["chat"]},
                 {"type":"number","column":"version","operator":">=","value":2},
                 {"type":"datetime","column":"createdAt","operator":"<","value":"2026-10-18T09:00:00+02:00"},
                 {"type":"datetime","column":"updatedAt","operator":">","value":1760000000000},
                 {"type":"arrayOptions","column":"labels","operator":"all of","value":["production","latest"]},
                 {"type":"stringObject","column":"config","key":"model","operator":"=","value":"m"},
                 {"type":"stringObject","column":"config","key":"tools","operator":"is not set"}]
                """;

        assertEquals(
                Optional.of(List.of(
                        new TextMatch("name", "contains", "bot"),
                        new TextMatch("id", "is not empty", ""),
                        new OptionsMatch("type", "none of", List.of("chat")),
                        new NumberMatch(">=", 2.0),
                        new TimeMatch("createdAt", "<", Instant.parse("2026-10-18T07:00:00Z")),
                        new TimeMatch("updatedAt", ">", Instant.ofEpochMilli(1_760_000_000_000L)),
                        new ArrayMatch("labels", "all of", List.of("production", "latest")),
                        new ConfigMatch("model", "=", "m"),
                        new ConfigMatch("tools", "is not set", ""))),
                PromptListFilter.parse(json));
    }

    /** Absent, blank or JSON {@code null} is no filter, so the simple parameters apply. */
    @Test
    void parse_absentBlankOrNullIsNoFilter() {
        assertEquals(Optional.empty(), PromptListFilter.parse(null));
        assertEquals(Optional.empty(), PromptListFilter.parse(" "));
        assertEquals(Optional.empty(), PromptListFilter.parse("null"));
        assertEquals(Optional.of(List.of()), PromptListFilter.parse("[]"));
    }

    /**
     * The other tool rewrites a legacy key-presence condition, an empty value with a substring operator, to "is set".
     * The bug: an empty {@code contains} reaches SQL as {@code ILIKE '%%'}, which also matches a key whose value is
     * an empty string but not one that is missing, so the two tools disagree.
     */
    @Test
    void parse_emptySubstringOnConfigMeansIsSet() {
        assertEquals(
                Optional.of(List.of(
                        new ConfigMatch("a", "is set", ""),
                        new ConfigMatch("b", "is set", ""),
                        new ConfigMatch("c", "is set", ""),
                        new ConfigMatch("d", "=", ""))),
                PromptListFilter.parse("""
                        [{"type":"stringObject","column":"config","key":"a","operator":"contains","value":""},
                         {"type":"stringObject","column":"config","key":"b","operator":"starts with","value":""},
                         {"type":"stringObject","column":"config","key":"c","operator":"ends with","value":""},
                         {"type":"stringObject","column":"config","key":"d","operator":"=","value":""}]
                        """));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{not json",
                "{\"type\":\"string\"}",
                "[1]",
                "[{\"type\":\"boolean\",\"column\":\"name\",\"operator\":\"=\",\"value\":true}]",
                "[{\"type\":\"string\",\"column\":\"version\",\"operator\":\"=\",\"value\":\"1\"}]",
                "[{\"type\":\"string\",\"column\":\"name\",\"operator\":\"like\",\"value\":\"a\"}]",
                "[{\"type\":\"string\",\"column\":\"name\",\"operator\":\"=\",\"value\":1}]",
                "[{\"type\":\"string\",\"column\":\"name\",\"operator\":\"=\"}]",
                "[{\"type\":\"stringOptions\",\"column\":\"type\",\"operator\":\"any of\",\"value\":[]}]",
                "[{\"type\":\"stringOptions\",\"column\":\"type\",\"operator\":\"any of\",\"value\":\"chat\"}]",
                "[{\"type\":\"stringOptions\",\"column\":\"type\",\"operator\":\"any of\",\"value\":[1]}]",
                "[{\"type\":\"number\",\"column\":\"version\",\"operator\":\"<>\",\"value\":1}]",
                "[{\"type\":\"number\",\"column\":\"version\",\"operator\":\"=\",\"value\":\"1\"}]",
                "[{\"type\":\"number\",\"column\":\"name\",\"operator\":\"=\",\"value\":1}]",
                "[{\"type\":\"datetime\",\"column\":\"createdAt\",\"operator\":\"=\",\"value\":\"2026-10-18T07:00:00Z\"}]",
                "[{\"type\":\"datetime\",\"column\":\"createdAt\",\"operator\":\"<\",\"value\":\"yesterday\"}]",
                "[{\"type\":\"datetime\",\"column\":\"createdAt\",\"operator\":\"<\",\"value\":true}]",
                "[{\"type\":\"arrayOptions\",\"column\":\"labels\",\"operator\":\"any of\",\"value\":[]}]",
                "[{\"type\":\"arrayOptions\",\"column\":\"name\",\"operator\":\"any of\",\"value\":[\"a\"]}]",
                "[{\"type\":\"stringObject\",\"column\":\"config\",\"operator\":\"=\",\"value\":\"a\"}]",
                "[{\"type\":\"stringObject\",\"column\":\"config\",\"key\":\"k\",\"operator\":\"is null\"}]",
                "[{\"type\":\"string\",\"column\":\"name\",\"operator\":\"=\",\"value\":\"a\\u0000\"}]"
            })
    void parse_refusesAnythingOutsideTheGrammar(String json) {
        TessaryException e = assertThrows(TessaryException.class, () -> PromptListFilter.parse(json));
        assertEquals(CommonError.INVALID_PARAMETER, e.error());
    }
}
