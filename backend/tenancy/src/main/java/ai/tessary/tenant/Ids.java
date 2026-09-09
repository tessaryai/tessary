// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Identifier + slug helpers. We use ULIDs as primary keys (lexicographically
 * sortable, 26 chars, URL-safe). Slugs are lowercase-dash kebab-case, truncated
 * to 60 chars.
 */
public final class Ids {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private Ids() {}

    /** Generate a ULID-shaped ID (26 chars, time-prefixed Crockford base32). */
    public static String ulid() {
        long ts = Instant.now().toEpochMilli();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (ts & 0x1F)];
            ts >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = CROCKFORD[RNG.nextInt(32)];
        }
        return new String(out);
    }

    /**
     * The largest ULID whose time prefix is {@code at} — the time prefix followed by the highest Crockford
     * character in every random position. Not an identifier: a comparison bound, so a keyset scan over
     * ULID primary keys can say "everything generated at or before this instant" without a second
     * timestamp column.
     *
     * <p>Used by the grading-spend sweeper to hold its watermark back from the present. A ULID's prefix is
     * <i>generation</i> time, not <i>commit</i> time, so a row minted now and committed seconds later would
     * be skipped forever by a watermark that had already advanced past it. Scanning only up to a bound in
     * the recent past closes that window.
     */
    public static String ulidUpperBound(Instant at) {
        long ts = at.toEpochMilli();
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (ts & 0x1F)];
            ts >>>= 5;
        }
        java.util.Arrays.fill(out, 10, 26, CROCKFORD[CROCKFORD.length - 1]);
        return new String(out);
    }

    /** Slugify "Acme Inc!" → "acme-inc". Falls back to "project" if empty. */
    public static String slugify(String input) {
        if (input == null) return "project";
        StringBuilder sb = new StringBuilder(input.length());
        boolean lastDash = true;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastDash = false;
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
                lastDash = false;
            } else if (!lastDash) {
                sb.append('-');
                lastDash = true;
            }
        }
        // strip trailing dash
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        if (sb.length() == 0) return "project";
        if (sb.length() > 60) sb.setLength(60);
        // strip trailing dash again after truncation
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
