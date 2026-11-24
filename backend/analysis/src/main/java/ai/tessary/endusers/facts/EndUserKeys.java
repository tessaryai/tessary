// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/** The salted user key and the UTC day number that key every fact row (spec section 3, conventions). */
public final class EndUserKeys {

    public static final int KEY_BYTES = 16;

    private static final String HMAC = "HmacSHA256";
    private static final long SECONDS_PER_DAY = 86_400;

    private EndUserKeys() {}

    /**
     * The first 16 bytes of HMAC-SHA-256 over the normalised ID in UTF-8, keyed by the project's salt. A trace with no
     * user ID counts under the all-zero key.
     */
    public static byte[] userKey(byte[] salt, @Nullable String rawId) {
        String id = normalise(rawId);
        if (id == null) return new byte[KEY_BYTES];
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(salt, HMAC));
            return Arrays.copyOf(mac.doFinal(id.getBytes(StandardCharsets.UTF_8)), KEY_BYTES);
        } catch (GeneralSecurityException e) {
            throw new TessaryException(CommonError.INTERNAL, e);
        }
    }

    /** Leading and trailing white space removed; what is left empty is no ID. */
    public static @Nullable String normalise(@Nullable String rawId) {
        if (rawId == null) return null;
        int start = 0;
        int end = rawId.length();
        while (start < end && isSpace(rawId.charAt(start))) start++;
        while (end > start && isSpace(rawId.charAt(end - 1))) end--;
        return start == end ? null : rawId.substring(start, end);
    }

    /** {@code floor(epoch seconds / 86400)}. */
    public static int dayIndex(Instant at) {
        return Math.toIntExact(Math.floorDiv(at.getEpochSecond(), SECONDS_PER_DAY));
    }

    // Python's str.strip() set, so a key matches the reference fold: Java's own whitespace test skips no-break spaces.
    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }
}
