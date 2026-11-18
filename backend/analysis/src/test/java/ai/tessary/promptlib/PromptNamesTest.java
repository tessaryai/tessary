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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The name, label, tag and variable rules every prompt write and every compile copy depends on. A rule looser than the
 * SQL CHECK turns a bad name into a 500; a looser label lets {@code latest} be stored; a variable scan that keeps
 * duplicates or dotted names disagrees with the SDKs.
 */
class PromptNamesTest {

    /** The pipe splits the SDK cache key, and slashes at an edge or doubled make an empty folder segment. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "/a", "a/", " /a", "a/ ", "a//b", "a|b"})
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

    static Stream<Arguments> spacedNames() {
        return Stream.of(
                Arguments.of("Support Agent", "Support Agent"),
                Arguments.of("  Support Agent  ", "Support Agent"),
                Arguments.of("\tteam/first name\n", "team/first name"),
                Arguments.of("\u00A0a b\u3000", "a b"),
                Arguments.of("\u001Ca\u2003b\u001F", "a\u2003b"),
                Arguments.of(" " + "x".repeat(255) + " ", "x".repeat(255)));
    }

    /**
     * Another prompt tool allows spaces in a name and trims the edges. The bugs: a prompt named {@code Support Agent}
     * there cannot be created here, and an untrimmed name reaches the CHECK, which refuses edge whitespace, as a 500.
     * The edge set covers every character either Java or the database counts as a space.
     */
    @ParameterizedTest
    @MethodSource("spacedNames")
    void requireName_acceptsInnerSpacesAndTrimsOuter(String given, String stored) {
        assertEquals(stored, PromptNames.requireName(given));
        assertEquals(stored, PromptNames.requireFolder(given));
    }

    /** The bug: the relaxed rule lets a pipe or a NUL through, which breaks the SDK cache key or the insert. */
    @Test
    void requireName_stillRefusesPipeSlashEdgesAndNul() {
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("Support | Agent"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName(" / a"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("team //a"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("a b\u0000"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName(" " + "x".repeat(256)));
    }

    /**
     * Names are shown in pages and other tools render them; another prompt tool refuses any text of the form
     * {@code <...>}. The bug: a name holding a tag is stored and later rendered as markup.
     */
    @Test
    void requireName_refusesAnHtmlTag() {
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("<b>bold</b>"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("team/a<br/>b"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireName("a <> b"));
        assertError(PromptsError.INVALID_NAME, () -> PromptNames.requireFolder("<script>"));
        assertEquals("a < b", PromptNames.requireName("a < b"));
        assertEquals("x>y<z", PromptNames.requireName("x>y<z"));
    }

    /** A looked-up name is trimmed like a stored one, so {@code " p "} finds {@code p}. */
    @Test
    void lookupName_trimsWithoutValidating() {
        assertEquals("Support Agent", PromptNames.lookupName("\u00A0Support Agent "));
        assertEquals("a|b", PromptNames.lookupName("a|b"));
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
