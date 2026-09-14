// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import java.util.List;
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
}
