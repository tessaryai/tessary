// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Name, label, tag and variable rules. A name is trimmed, then checked: inner whitespace is allowed, as in another
 * prompt tool. The trim takes every character Java or Postgres counts as a space, so a trimmed name always passes the
 * {@code ck_prompt_name} CHECK's edge rule. {@link #VARIABLE} has three copies (the frontend and both SDKs) that a
 * parity gate compares with this literal.
 */
public final class PromptNames {

    public static final Pattern NAME = Pattern.compile("^(?!/)(?!.*//)(?!.*/$)[^|]{1,255}$", Pattern.DOTALL);
    /** Text of the form {@code <...>}, the HTML-tag test another prompt tool applies to names. */
    public static final Pattern HTML_TAG = Pattern.compile("<[^<>]*>");

    public static final Pattern LABEL = Pattern.compile("^[a-z0-9_.-]{1,36}$");
    public static final Pattern TAG = Pattern.compile("^\\S{1,64}$", Pattern.UNICODE_CHARACTER_CLASS);
    public static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");
    public static final Pattern PLACEHOLDER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public static final String LATEST = "latest";
    public static final String PRODUCTION = "production";

    static final int MAX_TAGS = 50;

    private PromptNames() {}

    /** The trimmed name, which is what is stored; U+0000, which Postgres refuses in text, is checked on its own. */
    public static String requireName(String name) {
        String trimmed = lookupName(name);
        if (!NAME.matcher(trimmed).matches() || HTML_TAG.matcher(trimmed).find() || PromptBody.holdsNul(trimmed)) {
            throw new TessaryException(PromptsError.INVALID_NAME, name);
        }
        return trimmed;
    }

    /** A name as stored: trimmed of edge whitespace, unchecked, so a lookup by an untrimmed name finds the prompt. */
    public static String lookupName(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && isSpace(name.charAt(start))) start++;
        while (end > start && isSpace(name.charAt(end - 1))) end--;
        return name.substring(start, end);
    }

    /**
     * Java's whitespace (which covers U+001C to U+001F, as glibc's {@code [[:space:]]} does) plus the Unicode space
     * separators (U+00A0, U+2007, U+202F), so no edge character the CHECK refuses survives the trim.
     */
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** {@code latest} is derived from the newest version, so no write may name it. */
    public static void requireLabel(String label) {
        if (LATEST.equals(label)) throw new TessaryException(PromptsError.LATEST_RESERVED);
        if (!LABEL.matcher(label).matches()) throw new TessaryException(PromptsError.INVALID_LABEL, label);
    }

    /** Null is empty; order kept, duplicates dropped, at most {@value #MAX_TAGS} after dropping. */
    public static List<String> normalizeTags(@Nullable List<String> tags) {
        if (tags == null) return List.of();
        Set<String> kept = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!TAG.matcher(tag).matches() || PromptBody.holdsNul(tag)) {
                throw new TessaryException(PromptsError.INVALID_TAG, tag);
            }
            kept.add(tag);
        }
        if (kept.size() > MAX_TAGS) throw new TessaryException(PromptsError.INVALID_TAG, "more than 50 tags");
        return List.copyOf(kept);
    }

    /** A folder is the name up to its last slash; a name with no slash sits at the root, {@code ""}. */
    public static String folderOf(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    /**
     * A folder path is a name prefix, so the name rule applies to it, but it is used as given: {@code team /a} sits in
     * {@code "team "}, and a trim would point at the folder {@code team} instead. A blank path names no folder.
     */
    public static String requireFolder(String path) {
        if (path.isBlank()
                || !NAME.matcher(path).matches()
                || HTML_TAG.matcher(path).find()
                || PromptBody.holdsNul(path)) {
            throw new TessaryException(PromptsError.INVALID_NAME, path);
        }
        return path;
    }

    /** In first-appearance order, each name once. */
    public static List<String> variables(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(text);
        while (m.find()) found.add(m.group(1));
        return List.copyOf(found);
    }
}
