// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

/**
 * The coarse severity vocabulary shared by classifiers and detectors. Strings rather than an enum because the values
 * are persisted and the wire form is the contract.
 */
public final class Severity {

    private Severity() {}

    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String CRITICAL = "critical";
}
