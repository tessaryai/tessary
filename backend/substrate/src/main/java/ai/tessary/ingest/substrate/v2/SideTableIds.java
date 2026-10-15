// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import ai.tessary.open.hash.Sha256;

/**
 * Primary keys for {@code tool_call} and {@code retrieved_doc}, derived from the producer's
 * {@code (project, trace, span[, seq])} so a replayed batch collides and {@code ON CONFLICT DO NOTHING} dedupes it. Not
 * a ULID, whose time prefix differs on every redelivery; nothing orders these tables by id.
 */
final class SideTableIds {

    private static final int ID_HEX_CHARS = 32;

    private SideTableIds() {}

    static String toolCall(String projectId, String traceId, String spanId) {
        return "tc_" + digest("tc:" + projectId + ":" + traceId + ":" + spanId);
    }

    /** Keyed by the passage's 0-based position in the result. */
    static String retrievedDoc(String projectId, String traceId, String spanId, int seq) {
        return "rd_" + digest("rd:" + projectId + ":" + traceId + ":" + spanId + ":" + seq);
    }

    private static String digest(String material) {
        byte[] hash = Sha256.digest(material);
        StringBuilder hex = new StringBuilder(ID_HEX_CHARS);
        for (int i = 0; i < ID_HEX_CHARS / 2; i++) {
            hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return hex.toString();
    }
}
