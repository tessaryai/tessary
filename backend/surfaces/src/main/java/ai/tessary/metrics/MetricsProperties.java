// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds on metrics reads ({@code tessary.metrics.*}): each statement's timeout and sort memory, how many may run at
 * once so a dashboard's widgets cannot take the whole read pool, and how many of those one project may hold so it
 * cannot keep every other project out.
 */
@ConfigurationProperties(prefix = "tessary.metrics")
public record MetricsProperties(
        @DefaultValue("8000") long statementTimeoutMs,
        @DefaultValue("3") int maxConcurrentReads,
        @DefaultValue("2") int maxConcurrentReadsPerProject,
        @DefaultValue("64MB") String workMem) {}
