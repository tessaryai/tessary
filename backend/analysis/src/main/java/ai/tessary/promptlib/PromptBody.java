// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A prompt version's template. Text is one string; chat is a list of messages and placeholders, where a placeholder
 * takes a list of messages at compile time and holds no variables. Input accepts a {@code type} of {@code "chatmessage"}
 * or {@code "message"}, as other prompt tools send; storage and the wire never carry a {@code type} on a message.
 */
public sealed interface PromptBody permits PromptBody.Text, PromptBody.Chat {

    int MAX_CHARS = 200_000;
    int MAX_MESSAGES = 200;
    /** A text body counts its characters; a chat body counts its serialized JSON. */
    String TOO_LONG = "body exceeds 200000 characters";
    /** Postgres refuses U+0000 in text and jsonb, and its error would quote the value, so validation stops it first. */
    String NUL = "holds a NUL character (U+0000)";

    record Text(String value) implements PromptBody {}

    record Chat(List<Entry> entries) implements PromptBody {}

    sealed interface Entry permits Message, Placeholder {}

    record Message(String role, String content) implements Entry {}

    record Placeholder(String name) implements Entry {}

    /**
     * Validates what Jackson bound to an {@code Object} field: a {@code String}, or a list of maps. Throws
     * {@link PromptsError#INVALID_BODY} with the first reason found.
     */
    static PromptBody parse(PromptType type, @Nullable Object raw) {
        if (raw == null) throw invalid("body is required");
        return switch (type) {
            case TEXT -> parseText(raw);
            case CHAT -> {
                Chat chat = parseChat(raw);
                if (chat.toJson().length() > MAX_CHARS) throw invalid(TOO_LONG);
                yield chat;
            }
        };
    }

    /** The read path: {@code json} is a stored {@code prompt_version.body} this library validated on write. */
    static PromptBody fromJson(PromptType type, String json) {
        return switch (type) {
            case TEXT -> new Text(PromptJson.readString(json));
            case CHAT -> parseChat(PromptJson.readList(json));
        };
    }

    default String toJson() {
        return PromptJson.write(toWire());
    }

    /** A {@code String}, or a list of {@code {role, content}} and {@code {type: "placeholder", name}} maps. */
    default Object toWire() {
        return switch (this) {
            case Text t -> t.value();
            case Chat c -> {
                List<Map<String, String>> wire = new ArrayList<>(c.entries().size());
                for (Entry e : c.entries()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    switch (e) {
                        case Message msg -> {
                            m.put("role", msg.role());
                            m.put("content", msg.content());
                        }
                        case Placeholder p -> {
                            m.put("type", "placeholder");
                            m.put("name", p.name());
                        }
                    }
                    wire.add(m);
                }
                yield wire;
            }
        };
    }

    /** First-appearance order across the messages, each name once; placeholders contribute none. */
    default List<String> variables() {
        return switch (this) {
            case Text t -> PromptNames.variables(t.value());
            case Chat c -> {
                Set<String> found = new LinkedHashSet<>();
                for (Entry e : c.entries()) {
                    if (e instanceof Message msg) found.addAll(PromptNames.variables(msg.content()));
                }
                yield List.copyOf(found);
            }
        };
    }

    private static Text parseText(Object raw) {
        if (!(raw instanceof String s)) throw invalid("a text prompt body is a string");
        if (s.isEmpty()) throw invalid("body is empty");
        if (s.length() > MAX_CHARS) throw invalid(TOO_LONG);
        if (holdsNul(s)) throw invalid("body " + NUL);
        return new Text(s);
    }

    private static Chat parseChat(Object raw) {
        if (!(raw instanceof List<?> list)) throw invalid("a chat prompt body is an array of messages");
        if (list.isEmpty()) throw invalid("body is empty");
        if (list.size() > MAX_MESSAGES) throw invalid("body has more than 200 messages");
        List<Entry> entries = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            entries.add(parseEntry(i + 1, list.get(i)));
        }
        return new Chat(List.copyOf(entries));
    }

    private static Entry parseEntry(int n, @Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw invalid("message " + n + " is not an object");
        Object type = m.get("type");
        if (type == null || "message".equals(type) || "chatmessage".equals(type)) {
            requireKeys(n, m, Set.of("type", "role", "content"));
            if (!(m.get("role") instanceof String role) || role.isBlank()) {
                throw invalid("message " + n + " role must be a non-blank string");
            }
            if (!(m.get("content") instanceof String content)) {
                throw invalid("message " + n + " content must be a string");
            }
            if (holdsNul(role)) throw invalid("message " + n + " role " + NUL);
            if (holdsNul(content)) throw invalid("message " + n + " content " + NUL);
            return new Message(role, content);
        }
        if ("placeholder".equals(type)) {
            requireKeys(n, m, Set.of("type", "name"));
            if (!(m.get("name") instanceof String name)
                    || !PromptNames.PLACEHOLDER.matcher(name).matches()) {
                throw invalid("message " + n + " placeholder name is invalid");
            }
            return new Placeholder(name);
        }
        throw invalid("message " + n + " has unknown type " + type);
    }

    private static void requireKeys(int n, Map<?, ?> m, Set<String> allowed) {
        for (Object key : m.keySet()) {
            if (!allowed.contains(key)) throw invalid("message " + n + " has unknown key " + key);
        }
    }

    /** True when {@code value} holds U+0000, the one character Postgres cannot store in text or jsonb. */
    static boolean holdsNul(String value) {
        return value.indexOf('\0') >= 0;
    }

    private static TessaryException invalid(String reason) {
        return new TessaryException(PromptsError.INVALID_BODY, reason);
    }
}
