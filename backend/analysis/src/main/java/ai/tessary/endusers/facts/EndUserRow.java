// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import org.jspecify.annotations.Nullable;

/**
 * What one fold knows of a user ID for an {@code end_user} row: the trimmed text, the shape and the account cluster,
 * which stay when the text is deleted, and the day range seen. Keys and the cluster hash are lower-case hex.
 *
 * @param shapeSignature {@link ShapeSignature#wire()}
 * @param clusterHash {@link ShapeSignature#clusterHash}; null when the ID names no cluster
 * @param accountLabel the readable cluster text; null when the ID names no cluster
 */
public record EndUserRow(
        String userKey,
        String idText,
        String shapeSignature,
        @Nullable String clusterHash,
        @Nullable String accountLabel,
        int firstDay,
        int lastDay) {}
