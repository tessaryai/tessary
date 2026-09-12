// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

/**
 * A credential redaction removed from a span on the way in: which corpus rule matched, in which field, and
 * whether that rule anchors on a literal the provider stamps into the credential.
 *
 * <p>This is the record a leak detector reads afterwards. Redaction replaces a credential with a token before
 * anything is stored, so by the time a detector sweeps the span the credential is gone, and a token like
 * {@code [REDACTED_SECRET]} cannot say whether it replaced an AWS key or a word after {@code password=}. The
 * stamp can, and it carries nothing of the credential itself.
 *
 * @param rule the gitleaks rule id, such as {@code aws-access-token}
 * @param field {@link #INPUT}, {@link #OUTPUT} or {@link #ATTRIBUTES}
 * @param anchored whether the rule matched on a literal the provider puts in the credential, rather than on a
 *     vendor name near a random-looking string
 */
public record RedactionStamp(String rule, String field, boolean anchored) {

    /** The span's input, or its input messages. */
    public static final String INPUT = "input";

    /** The span's output, or its output messages. */
    public static final String OUTPUT = "output";

    /** A string attribute on the span. */
    public static final String ATTRIBUTES = "attributes";
}
