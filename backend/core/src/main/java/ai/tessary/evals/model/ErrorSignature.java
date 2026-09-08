// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Collapse an error message to the pattern it is an instance of — the one normalizer, shared by the two
 * layers that need it.
 *
 * <p><b>Why it lives in {@code core}.</b> It was written for the tool-error classifier
 * ({@code classifier/toolerror/ToolFailure}, in {@code analysis}), and the ingest edge now needs the same
 * function to derive a short {@code span.error_type} when a producer ships no {@code error.type}
 * attribute. {@code substrate} sits below {@code analysis} and cannot import it, and a second copy at the
 * write site would mean two definitions of "these two errors are the same kind" drifting apart — which is
 * exactly the failure the classifier's own design notes forbid. So the definition moved down and
 * {@code ToolFailure} delegates.
 *
 * <p><b>Deliberately not clustering.</b> No edit distance, no embedding, no learned log template. Two
 * messages group if and only if they normalize to the same string. That is reproducible from the message
 * alone, explainable to a partner in one sentence, and free.
 *
 * <p>Placeholders are literal words rather than a hash because the result is <b>user-visible</b>: it is
 * rendered verbatim in a finding's pattern breakdown, and it is what a span carries in {@code error_type}
 * when the producer named no class.
 */
public final class ErrorSignature {

    private ErrorSignature() {}

    /** The signature a failure carrying no usable message at all gets, so it still groups with its kin. */
    public static final String UNDESCRIBED = "<undescribed>";

    /**
     * Longest signature kept. Bounded because signatures are map keys inside a persisted evidence blob,
     * and a stack trace pasted into a status message would otherwise be one key.
     */
    public static final int MAX_SIGNATURE_LENGTH = 120;

    // Order matters and is asserted by the tests: each pattern must run before any pattern that would
    // eat part of its match. A URL contains digits and a UUID contains hex, so <num> and <hex> run last.
    private static final Pattern URL = Pattern.compile("\\b[a-z][a-z0-9+.-]*://\\S+");
    private static final Pattern IP_PORT = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    private static final Pattern UUID =
            Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"|`[^`]*`");
    private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{8,}\\b");
    // No trailing \b: a duration is written "30014ms", and between '4' and 'm' there is no word boundary
    // to find, so a bounded-both-ends pattern leaves every timeout message carrying its own milliseconds
    // and never grouping with the next one. The LEADING \b stays — without it the "2" in "/v2/items"
    // becomes a placeholder and a URL path turns into noise.
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
