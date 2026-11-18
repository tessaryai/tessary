// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.promptlib.PromptBody.Chat;
import ai.tessary.promptlib.PromptBody.Message;
import ai.tessary.promptlib.PromptBody.Placeholder;
import ai.tessary.promptlib.PromptBody.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The body shape is what the SDKs compile against: a chat body that kept an input {@code type} key, accepted a
 * non-string content, or dropped a placeholder would break every client fetch, and a size cap that never fired would
 * let one write store megabytes.
 */
class PromptBodyTest {

    @Test
    void text_requiresString() {
        assertReason("a text prompt body is a string", PromptType.TEXT, List.of());
    }

    @Test
    void text_rejectsMissingEmptyAndOversize() {
        assertReason("body is required", PromptType.TEXT, null);
        assertReason("body is empty", PromptType.TEXT, "");
        assertReason("body exceeds 200000 characters", PromptType.TEXT, "x".repeat(200_001));
        assertEquals(new Text("x".repeat(200_000)), PromptBody.parse(PromptType.TEXT, "x".repeat(200_000)));
    }

    @Test
    void chat_acceptsMessageChatmessageAndPlaceholder_roundTrip() {
        List<Object> raw = List.of(
                map("type", "chatmessage", "role", "system", "content", "x"),
                map("type", "placeholder", "name", "h"),
                map("role", "user", "content", "y"),
                map("type", "message", "role", "assistant", "content", "z"));

        PromptBody body = PromptBody.parse(PromptType.CHAT, raw);

        assertEquals(
                new Chat(List.of(
                        new Message("system", "x"),
                        new Placeholder("h"),
                        new Message("user", "y"),
                        new Message("assistant", "z"))),
                body);
        assertEquals(
                List.of(
                        Map.of("role", "system", "content", "x"),
                        Map.of("type", "placeholder", "name", "h"),
                        Map.of("role", "user", "content", "y"),
                        Map.of("role", "assistant", "content", "z")),
                body.toWire());
        assertEquals(body, PromptBody.fromJson(PromptType.CHAT, body.toJson()));
    }

    @Test
    void text_roundTripsThroughJson() {
        PromptBody body = PromptBody.parse(PromptType.TEXT, "Hi {{name}}");
        assertEquals("Hi {{name}}", body.toWire());
        assertEquals(body, PromptBody.fromJson(PromptType.TEXT, body.toJson()));
    }

    @Test
    void chat_rejectsUnknownKeyNonStringContentAndBadType() {
        assertReason(
                "message 1 content must be a string",
                PromptType.CHAT,
                List.of(map("role", "user", "content", List.of("a"))));
        assertReason(
                "message 1 has unknown key foo",
                PromptType.CHAT,
                List.of(map("role", "user", "content", "a", "foo", "b")));
        assertReason("message 1 has unknown type tool", PromptType.CHAT, List.of(map("type", "tool", "content", "a")));
    }

    static List<Object[]> badChatBodies() {
        List<Object> twoHundredOne = new ArrayList<>();
        for (int i = 0; i < 201; i++) twoHundredOne.add(map("role", "user", "content", "a"));
        return List.of(
                new Object[] {"a chat prompt body is an array of messages", "hello"},
                new Object[] {"body is empty", List.of()},
                new Object[] {"body has more than 200 messages", twoHundredOne},
                new Object[] {"message 2 is not an object", List.of(map("role", "user", "content", "a"), "x")},
                new Object[] {"message 1 role must be a non-blank string", List.of(map("role", " ", "content", "a"))},
                new Object[] {"message 1 role must be a non-blank string", List.of(map("content", "a"))},
                new Object[] {"message 1 has unknown type 5", List.of(map("type", 5, "role", "user", "content", "a"))},
                new Object[] {"message 1 placeholder name is invalid", List.of(map("type", "placeholder", "name", "1x"))
                },
                new Object[] {"message 1 placeholder name is invalid", List.of(map("type", "placeholder"))},
                new Object[] {
                    "message 1 has unknown key role", List.of(map("type", "placeholder", "name", "h", "role", "user"))
                },
                new Object[] {
                    "body exceeds 200000 characters",
                    List.of(
                            map("role", "user", "content", "x".repeat(100_000)),
                            map("role", "user", "content", "x".repeat(100_000)))
                });
    }

    @ParameterizedTest
    @MethodSource("badChatBodies")
    void chat_rejectsEachMalformedShapeWithItsReason(String reason, Object raw) {
        assertReason(reason, PromptType.CHAT, raw);
    }

    /**
     * Postgres refuses U+0000 in jsonb (22P05) and its error context quotes the body, which the fallback handler then
     * logs with a 500. The bug: a body with a NUL character passes validation and reaches the database.
     */
    @Test
    void nulCharacter_isInvalidBodyBeforeTheDatabaseSeesIt() {
        assertReason("body holds a NUL character (U+0000)", PromptType.TEXT, "secret system text\u0000");
        assertReason(
                "message 1 content holds a NUL character (U+0000)",
                PromptType.CHAT,
                List.of(map("role", "user", "content", "secret\u0000")));
        assertReason(
                "message 2 role holds a NUL character (U+0000)",
                PromptType.CHAT,
                List.of(map("role", "user", "content", "a"), map("role", "us\u0000er", "content", "b")));
    }

    @Test
    void variables_ignorePlaceholders() {
        PromptBody body = PromptBody.parse(
                PromptType.CHAT,
                List.of(
                        map("type", "placeholder", "name", "history"),
                        map("role", "user", "content", "{{q}}"),
                        map("role", "user", "content", "{{q}} {{r}}")));
        assertEquals(List.of("q", "r"), body.variables());
        assertEquals(List.of("a"), PromptBody.parse(PromptType.TEXT, "{{a}}").variables());
    }

    private static void assertReason(String reason, PromptType type, @Nullable Object raw) {
        TessaryException e = assertThrows(TessaryException.class, () -> PromptBody.parse(type, raw));
        assertEquals(PromptsError.INVALID_BODY, e.error());
        assertEquals("Invalid prompt body: " + reason, e.getMessage());
    }

    /** Insertion-ordered, as Jackson hands a JSON object to an {@code Object} field. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
