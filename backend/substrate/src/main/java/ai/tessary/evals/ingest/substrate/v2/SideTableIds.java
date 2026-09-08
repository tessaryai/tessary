// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derived primary keys for the two side tables the v2 write path owns ({@code tool_call},
 * {@code retrieved_doc}).
 *
 * <p><b>Why derived and not random.</b> Both tables used to dedupe an at-least-once redelivery on a
 * natural key that led with the surrogate {@code observation_id} — the id the v1 enricher minted in the
 * same loop. The v2 writer has no such id and writes {@code NULL} there, and two NULLs are distinct in a
 * unique index, so that key stops deduping the moment ownership transfers. Deriving the primary key from
 * the producer's own {@code (project, trace, span[, seq])} puts the property back where it cannot rot: a
 * replayed batch computes the same id, collides on the primary key, and {@code ON CONFLICT DO NOTHING}
 * makes it the no-op it always was.
 *
 * <p>Deliberately not a ULID: a ULID's time prefix encodes when the row was MINTED, which for a redelivery
 * is a different instant every time — precisely the property that makes it useless as an idempotency key.
 * Nothing orders these tables by id (reads sort on {@code started_at}/{@code seq}), so the sortability a
 * ULID buys is not being given up.
 */
final class SideTableIds {

    /** Wide enough that a collision is not a thing that happens; short enough to stay readable in a log. */
    private static final int ID_HEX_CHARS = 32;

    private SideTableIds() {}

    /** The id of the one tool call extracted from a {@code tool}-kind span. */
    static String toolCall(String projectId, String traceId, String spanId) {
        return "tc_" + digest("tc:" + projectId + ":" + traceId + ":" + spanId);
    }

    /** The id of one retrieved passage of a retrieval span, keyed by its 0-based position in the result. */
    static String retrievedDoc(String projectId, String traceId, String spanId, int seq) {
        return "rd_" + digest("rd:" + projectId + ":" + traceId + ":" + spanId + ":" + seq);
    }

    private static String digest(String material) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform spec on every JVM this can run on.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] hash = sha256.digest(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(ID_HEX_CHARS);
        for (int i = 0; i < ID_HEX_CHARS / 2; i++) {
            hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return hex.toString();
    }
}
