// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

/**
 * Published after a new {@link AlertEventRow} commits; the alert channels listen AFTER_COMMIT with
 * {@code fallbackExecution = true}. Only published when {@code insertIfAbsent} wrote the row, so N backends fire once.
 */
public record AlertFiredEvent(AlertEventRow row) {}
