// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

/**
 * One user ID's first traces on one route in the learn window, in time order, capped at
 * {@link ai.tessary.endusers.judge.JudgeConstants#PAIR_TEST_MAX_TRACES}: the minute of the day and the day of each.
 */
public record RouteSample(int routeNo, int[] minutes, int[] days) {}
