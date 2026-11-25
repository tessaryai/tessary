// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Opaque page cursors: integers and an md5 tie-breaker joined by dots, base64url without padding. A cursor never
 * holds a user key or ID text, because access logs keep full URIs.
 */
final class Keyset {

    private static final Pattern TIE = Pattern.compile("[0-9a-f]{32}");

    private Keyset() {}

    static String encode(String tie, int... numbers) {
        StringBuilder text = new StringBuilder();
        for (int number : numbers) text.append(number).append('.');
        text.append(tie);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** @return the numbers, then the tie as the last element */
    static String[] decode(String cursor, int numbers) {
        String[] parts;
        try {
            parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\.", -1);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (parts.length != numbers + 1 || !TIE.matcher(parts[numbers]).matches()) throw invalid();
        return parts;
    }

    static int number(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static TessaryException invalid() {
        return new TessaryException(CommonError.INVALID_PARAMETER, "cursor");
    }
}
