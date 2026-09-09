// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} for the observer drainer. Kept separate from
 * {@link AsyncConfig} so the scheduling concern is isolated and tests that
 * don't want a live scheduler can exclude this config.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
