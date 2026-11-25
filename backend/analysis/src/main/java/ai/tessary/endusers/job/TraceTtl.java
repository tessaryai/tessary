// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.retention.RetentionResolver;

/** The project's resolved trace TTL in days; 0 keeps traces for good. */
final class TraceTtl {

    private TraceTtl() {}

    static int days(RetentionResolver retention, String projectId) {
        return retention.resolve(projectId).stream()
                .filter(r -> r.dataClass() == RetentionResolver.DataClass.TRACES)
                .findFirst()
                .orElseThrow()
                .ttlDays();
    }
}
