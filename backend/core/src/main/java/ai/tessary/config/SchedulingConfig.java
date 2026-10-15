// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Separate from {@link AsyncConfig} so tests can exclude the live scheduler. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
