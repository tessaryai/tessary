// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Collapses an error message to its pattern. In {@code core} so ingest and the tool-error classifier share one
 * definition. Not clustering: two messages group iff they normalize to the same string. Placeholders are words, not
 * hashes, because the result is user-visible.
 */
public final class ErrorSignature {

    private ErrorSignature() {}

    public static final String UNDESCRIBED = "<undescribed>";

    /** Signatures are map keys in a persisted blob; a pasted stack trace would otherwise be one key. */
    public static final int MAX_SIGNATURE_LENGTH = 120;

    // Order matters and is asserted by tests: a URL contains digits and a UUID hex, so <num> and <hex> run last.
    private static final Pattern URL = Pattern.compile("\\b[a-z][a-z0-9+.-]*://\\S+");
    private static final Pattern IP_PORT = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    private static final Pattern UUID =
            Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"|`[^`]*`");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{8,}\\b");
    // No trailing \b: "30014ms" has no boundary between '4' and 'm'. The leading \b keeps "/v2/items" intact.
    private static final Pattern NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * The pattern {@code message} is an instance of.
     *
     * <pre>
     * "HTTP 500 upstream from https://api.stripe.com/v1/charges/ch_3Ox9aB"
     *     -&gt; "http &lt;num&gt; upstream from &lt;url&gt;"
     * "Tool 'search_docs' failed after 30014ms (attempt 3/3)"
     *     -&gt; "tool &lt;str&gt; failed after &lt;num&gt;ms (attempt &lt;num&gt;/&lt;num&gt;)"
     * </pre>
     */
    public static String signature(@Nullable String message) {
        if (message == null || message.isBlank()) return UNDESCRIBED;
        String s = message.toLowerCase(Locale.ROOT);
        s = URL.matcher(s).replaceAll("<url>");
        s = IP_PORT.matcher(s).replaceAll("<ip>");
        s = UUID.matcher(s).replaceAll("<uuid>");
        s = QUOTED.matcher(s).replaceAll("<str>");
        s = HEX.matcher(s).replaceAll("<hex>");
        s = NUMBER.matcher(s).replaceAll("<num>");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        if (s.isEmpty()) return UNDESCRIBED;
        return s.length() <= MAX_SIGNATURE_LENGTH
                ? s
                : s.substring(0, MAX_SIGNATURE_LENGTH).trim() + "…";
    }
}
