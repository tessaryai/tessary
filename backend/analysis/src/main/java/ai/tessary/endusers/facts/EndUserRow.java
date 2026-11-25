// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import org.jspecify.annotations.Nullable;

/**
 * What one fold knows of a user ID for an {@code end_user} row: the trimmed text, the shape and the account cluster
 * hash, which stay when the text is deleted, and the day range seen. The text is the only readable part of the ID the
 * fold stores (plan 3.7). Keys and the cluster hash are lower-case hex.
 *
 * @param shapeSignature {@link ShapeSignature#wire()}
 * @param clusterHash {@link ShapeSignature#clusterHash}; null when the ID names no cluster
 * @param sessionTokenLink spec 3.1 {@code token_link}: every session of the ID's traces holds an 8-character piece of
 *     the ID text ({@link FoldFacts#hasLink}); true when no trace of the ID names a session, as the reference's
 *     {@code all()} over no sessions
 */
public record EndUserRow(
        String userKey,
        String idText,
        String shapeSignature,
        @Nullable String clusterHash,
        int firstDay,
        int lastDay,
        boolean sessionTokenLink) {}
