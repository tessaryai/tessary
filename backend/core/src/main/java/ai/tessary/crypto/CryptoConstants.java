// SPDX-License-Identifier: Apache-2.0
package ai.tessary.crypto;

/** AES-256-GCM parameters for {@link SecretBox} and {@code SessionCipher}, which must stay identical. */
public final class CryptoConstants {

    private CryptoConstants() {}

    public static final String ALG = "AES";

    public static final String XFORM = "AES/GCM/NoPadding";

    /** The GCM-recommended 96-bit nonce. */
    public static final int IV_LEN = 12;

    public static final int TAG_BITS = 128;
}
