// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Opaque, resumable preview cursor. Provider page cursors are page-granular, but
 * a preview page returns an exact number of <em>whole traces</em>, so resuming may
 * have to re-fetch the page that straddles the trace boundary and skip the traces
 * already emitted from it. This pairs the provider's page token with that skip count.
 *
 * <p>Wire form is URL-safe base64 of {@code "<skip>\n<token>"} so the frontend treats
 * it as one opaque string. {@code token == null} means "from the beginning".
 */
public record PreviewCursor(@Nullable String token, int skip) {

    public static PreviewCursor decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) return new PreviewCursor(null, 0);
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int nl = raw.indexOf('\n');
            if (nl < 0) return new PreviewCursor(null, 0);
            int skip = Math.max(0, Integer.parseInt(raw.substring(0, nl)));
            String token = raw.substring(nl + 1);
            return new PreviewCursor(token.isEmpty() ? null : token, skip);
        } catch (RuntimeException e) {
            return new PreviewCursor(null, 0);
        }
    }

    public static String encode(@Nullable String token, int skip) {
        String raw = skip + "\n" + (token == null ? "" : token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
