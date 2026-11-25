// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

/** Which days make an ID's day series (spec 2): paced days of a person, paced days under the lenient fallback, automation days, or every day. */
public enum ActivityBasis {
    HUMAN,
    LENIENT,
    UNATTENDED,
    MACHINE
}
