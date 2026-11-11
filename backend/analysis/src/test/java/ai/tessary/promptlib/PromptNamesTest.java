// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The name, label, tag and variable rules every prompt write and every compile copy depends on. A rule looser than the
 * SQL CHECK turns a bad name into a 500; a looser label lets {@code latest} be stored; a variable scan that keeps
 * duplicates or dotted names disagrees with the SDKs.
 */
class PromptNamesTest {

    /** Java's {@code \s} under UNICODE_CHARACTER_CLASS covers the no-break and ideographic spaces SQL also rejects. */
    @ParameterizedTest
    @ValueSource(strings = {"", "/a", "a/", "a//b", "a b", "a|b", "a b", "a b", "a　b", "a\tb"})
    void name_matchesTheSqlCheck_rejects(String name) {
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName(name));
    }

    @Test
    void name_matchesTheSqlCheck() {
        assertDoesNotThrow(() -> PromptNames.requireName("a"));
        assertDoesNotThrow(() -> PromptNames.requireName("team/a_b.c"));
        assertDoesNotThrow(() -> PromptNames.requireName("x".repeat(255)));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("x".repeat(256)));
    }

    /**
     * {@code \s} does not match U+0000, and Postgres refuses a NUL in text and in jsonb with a 500. The bug: a name,
     * folder or tag with a NUL character passes validation and reaches the database.
     */
    @Test
    void nulCharacter_isRejectedInNamesFoldersAndTags() {
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("a\u0000b"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireFolder("team\u0000"));
        assertError(PromptsError.INVALID_TAG, () -> PromptNames.normalizeTags(List.of("ok", "t\u0000")));
    }

    @Test
    void label_rejectsLatestAndUppercase() {
        assertError(PromptsError.LATEST_RESERVED, () -> PromptNames.requireLabel("latest"));
        assertError(PromptsError.INVALID_LABEL, () -> PromptNames.requireLabel("Prod"));
        assertError(PromptsError.INVALID_LABEL, () -> PromptNames.requireLabel("a".repeat(37)));
        assertError(PromptsError.INVALID_LABEL, () -> PromptNames.requireLabel(""));
        assertDoesNotThrow(() -> PromptNames.requireLabel("prod-1.0_a"));
    }

    @Test
    void variables_areOrderedAndDeduplicated() {
        assertEquals(List.of("b", "a"), PromptNames.variables("{{b}} {{ a }} {{b}} {{1x}} {{a.b}}"));
    }

    @Test
    void tags_dedupeAndCap() {
        assertEquals(List.of("a", "b"), PromptNames.normalizeTags(List.of("a", "a", "b")));
        assertEquals(List.of(), PromptNames.normalizeTags(null));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) many.add("t" + i);
        assertError(PromptsError.INVALID_TAG, () -> PromptNames.normalizeTags(many));
        assertError(PromptsError.INVALID_TAG, () -> PromptNames.normalizeTags(List.of("a b")));
    }

    /** A folder is everything before the last slash, so {@code team/a/b} lists under {@code team/a}. */
    @Test
    void folderOf_isTheTextBeforeTheLastSlash() {
        assertEquals("team/a", PromptNames.folderOf("team/a/b"));
        assertEquals("", PromptNames.folderOf("b"));
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(TessaryException.class, call).error());
    }
}
