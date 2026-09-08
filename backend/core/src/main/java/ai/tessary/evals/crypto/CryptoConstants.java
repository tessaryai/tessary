// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.crypto;

/**
 * Shared AES-256-GCM parameters used by every cipher in the stack
 * ({@link SecretBox} for ingestion credentials, {@code SessionCipher} for the
 * session cookie). Centralised so the cipher parameters can only ever change in
 * one place — both ciphers MUST stay on identical algorithm/IV/tag settings.
 */
public final class CryptoConstants {

    private CryptoConstants() {}

    /** Key algorithm for {@link javax.crypto.spec.SecretKeySpec}. */
    public static final String ALG = "AES";

    /** Cipher transformation: AES in GCM mode, no padding. */
    public static final String XFORM = "AES/GCM/NoPadding";

    /** GCM IV length in bytes (96-bit nonce, the GCM-recommended size). */
    public static final int IV_LEN = 12;

    /** GCM authentication-tag length in bits. */
    public static final int TAG_BITS = 128;
}
