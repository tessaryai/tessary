// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

/**
 * The platform's coarse severity vocabulary — the {@code classifier} definition's and a detector's, which
 * are the same three words.
 *
 * <p>Lives in {@code model} because it is a vocabulary rather than behaviour, and because separate
 * packages had independently declared it: {@code classifier.Detection.Severity} and another feature's
 * constants. Two copies agreeing today is two copies that can disagree tomorrow,
 * and a consumer of one had to import a whole feature package to read three strings.
 *
 * <p>Strings rather than an enum: these values are PERSISTED (in {@code signal.severity} and a
 * verdict's payload), so the wire form is the contract and an enum would only add a conversion at
 * every boundary.
 */
public final class Severity {

    private Severity() {}

    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String CRITICAL = "critical";
}
