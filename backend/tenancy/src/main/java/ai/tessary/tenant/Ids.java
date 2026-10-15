// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.security.SecureRandom;
import java.time.Instant;

/** ULID ids and kebab-case slugs truncated to 60 chars. */
public final class Ids {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private Ids() {}

    public static String ulid() {
        return ulid(Instant.now());
    }

    /** Lets the time prefix be pinned. */
    static String ulid(Instant at) {
        long ts = at.toEpochMilli();
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

    /** "Acme Inc!" → "acme-inc"; "project" if empty. */
    public static String slugify(String input) {
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
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        if (sb.length() == 0) return "project";
        if (sb.length() > 60) sb.setLength(60);
        // Truncation can expose a new trailing dash.
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
