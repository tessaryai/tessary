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
 * Name, label, tag and variable rules. {@link #NAME} is at least as strict as the {@code ck_prompt_name} CHECK: under
 * UNICODE_CHARACTER_CLASS {@code \s} also matches U+00A0, U+2003 and U+3000. {@link #VARIABLE} has three copies (the
 * frontend and both SDKs) that a parity gate compares with this literal.
 */
public final class PromptNames {

    public static final Pattern NAME =
            Pattern.compile("^(?!/)(?!.*//)(?!.*/$)[^|\\s]{1,255}$", Pattern.UNICODE_CHARACTER_CLASS);
    public static final Pattern LABEL = Pattern.compile("^[a-z0-9_.-]{1,36}$");
    public static final Pattern TAG = Pattern.compile("^\\S{1,64}$", Pattern.UNICODE_CHARACTER_CLASS);
    public static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");
    public static final Pattern PLACEHOLDER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public static final String LATEST = "latest";
    public static final String PRODUCTION = "production";

    static final int MAX_TAGS = 50;

    private PromptNames() {}

    /** {@code \s} does not cover U+0000, which Postgres refuses in text, so it is checked on its own. */
    public static void requireName(String name) {
        if (!NAME.matcher(name).matches() || PromptBody.holdsNul(name)) {
            throw new TessaryException(PromptsError.INVALID_NAME, name);
        }
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

    /** A folder path is a name prefix, so the name rule applies to it. */
    public static void requireFolder(String path) {
        requireName(path);
    }

    /** In first-appearance order, each name once. */
    public static List<String> variables(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(text);
        while (m.find()) found.add(m.group(1));
        return List.copyOf(found);
    }
}
