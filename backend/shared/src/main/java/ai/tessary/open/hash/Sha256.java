// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.hash;

import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 without the checked exception every Java platform makes impossible. */
public final class Sha256 {

    private Sha256() {}

    public static byte[] digest(byte[] bytes) {
        return newDigest().digest(bytes);
    }

    /** SHA-256 of the UTF-8 bytes of {@code text}. */
    public static byte[] digest(String text) {
        return digest(text.getBytes(StandardCharsets.UTF_8));
    }

    @ExcludeFromJacocoGeneratedReport("SHA-256 is required of every Java platform, so the catch cannot fire")
    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}
