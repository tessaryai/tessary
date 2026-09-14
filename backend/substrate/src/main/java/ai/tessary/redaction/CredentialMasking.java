// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Masks every gitleaks-recognised credential in a text with its {@code prefix…suffix} form, for a
 * surface that must never hand a raw credential to a browser or MCP client on a project that has
 * redaction disabled (or on text captured before a redaction rule existed). Cheap by construction:
 * one corpus scan over the text, same as the ingest path already runs on whole payloads.
 */
public final class CredentialMasking {

    private CredentialMasking() {}

    public static @Nullable String mask(@Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        List<GitleaksCorpus.Finding> findings = GitleaksCorpus.get().find(text);
        if (findings.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (GitleaksCorpus.Finding f : findings) {
            if (f.start() < cursor) continue;
            out.append(text, cursor, f.start());
            out.append(maskSecret(text.substring(f.start(), f.end())));
            cursor = f.end();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /** {@code prefix…suffix}, or a bare ellipsis when the secret is too short to leave a gap between them. */
    private static String maskSecret(String secret) {
        if (secret.length() <= 8) return "…";
        return secret.substring(0, 4) + "…" + secret.substring(secret.length() - 4);
    }

    /**
     * A single credential's provider-shaped separator: the run of letters and digits gitleaks anchors
     * rules on ({@code AKIA}, {@code ghp_}, {@code xoxb-}, {@code sk-}), up to and including the
     * separator that ends it. Matched at the start of the raw credential only.
     */
    private static final Pattern PROVIDER_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9]{1,7}[_-]");

    /**
     * The masked form of one matched credential, for a surface that names a specific leaking key rather
     * than scrubbing free text: the recognisable provider prefix ({@code AKIA}, {@code ghp_}) when the
     * shape has one, else the first 4 characters, plus "…" plus the last 4 characters. Distinct from
     * {@link #mask}, which scrubs every credential inside a block of prose for display; this names ONE
     * credential a caller already isolated, so its own key can be told apart from another leaking one
     * without reconstructing either.
     *
     * <p>Guarded the same way {@link #maskSecret} is: a prefix and a suffix window only ever reveal a
     * caller-recognisable sliver, never the whole credential. When the match is too short for a 4+4
     * window to leave anything hidden, only the provider prefix (or nothing) survives.
     */
    public static String maskedKey(String rawMatch) {
        if (rawMatch.isEmpty()) return "…";
        Matcher m = PROVIDER_PREFIX.matcher(rawMatch);
        boolean providerPrefix = m.lookingAt();
        String prefix = providerPrefix ? m.group() : rawMatch.substring(0, Math.min(4, rawMatch.length()));
        // At least 4 characters must sit between the prefix and the suffix, or a suffix is not shown at
        // all: two 4-character windows over a short match otherwise cover the whole credential, only
        // cosmetically split by the ellipsis. A recognisable provider prefix is a known constant, not
        // part of the secret, so it still surfaces on its own when the suffix is withheld.
        if (rawMatch.length() - prefix.length() < 8) return (providerPrefix ? prefix : "") + "…";
        String suffix = rawMatch.substring(rawMatch.length() - 4);
        return prefix + "…" + suffix;
    }
}
