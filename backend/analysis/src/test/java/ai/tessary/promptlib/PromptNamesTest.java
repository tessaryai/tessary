// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import java.time.Duration;
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
 * duplicates or reads a name another copy does not disagrees with the SDKs.
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
     * A name may hold spaces, and its edges are trimmed. The bugs: a prompt named {@code Support Agent}
     * there cannot be created here, and an untrimmed name reaches the CHECK, which refuses edge whitespace, as a 500.
     * The edge set covers every character either Java or the database counts as a space.
     */
    @ParameterizedTest
    @MethodSource("spacedNames")
    void requireName_acceptsInnerSpacesAndTrimsOuter(String given, String stored) {
        assertEquals(stored, PromptNames.requireName(given));
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
     * Names are shown in pages that render them, so any text of the form {@code <...>} is refused. The bug: a name holding a tag is stored and later rendered as markup.
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
        assertEquals(List.of("b", "a", "1x", "a.b"), PromptNames.variables("{{b}} {{ a }} {{b}} {{1x}} {{a.b}}"));
    }

    /**
     * The rule all four copies share: any trimmed, non-blank text with no brace between double braces. Bug: a dotted,
     * hyphenated or spaced name is not listed, so the Variables panel and the SDK compile skip it with no error.
     */
    static Stream<Arguments> variableRule() {
        return Stream.of(
                Arguments.of("{{user.name}}", List.of("user.name")),
                Arguments.of("{{first-name}} {{ first name }}", List.of("first-name", "first name")),
                Arguments.of("{{}} {{   }}", List.of()),
                Arguments.of("{{{name}}}", List.of("name")),
                Arguments.of("{{a{b}}", List.of()),
                Arguments.of("{{\"a\":1}}", List.of("\"a\":1")));
    }

    @ParameterizedTest
    @MethodSource("variableRule")
    void variables_takeAnyNonBlankInnerText(String text, List<String> expected) {
        assertEquals(expected, PromptNames.variables(text));
    }

    @Test
    void variables_anUnclosedOpeningOverALongBodyReturnsQuickly() {
        // Bug: the scan backtracks quadratically, and a long body with an unclosed {{ holds a request thread for
        // minutes now that the body has no size cap below the 1 MiB request bound.
        String body = "{{" + " ".repeat(200_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(List.of(), PromptNames.variables(body)));
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
