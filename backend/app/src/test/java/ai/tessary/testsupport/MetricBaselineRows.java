// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.classifier.metric.MetricBaselineRow;
import ai.tessary.tenant.Ids;
import java.time.Instant;

public final class MetricBaselineRows {

    private MetricBaselineRows() {}

    public static MetricBaselineRow fresh(
            String projectId, String classifierId, String measure, String bucketKey, String state) {
        String now = Instant.now().toString();
        return new MetricBaselineRow(
                Ids.ulid(),
                projectId,
                classifierId,
                measure,
                MetricBaselineRow.BucketKind.CALL_SITE,
                bucketKey,
                state,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                now,
                now);
    }
}
