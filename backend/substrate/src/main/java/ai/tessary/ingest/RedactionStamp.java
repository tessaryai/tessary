// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import org.jspecify.annotations.Nullable;

/**
 * A credential redaction made on the way in, read later by leak detectors: once replaced by a token the credential is
 * gone, but the stamp still says which rule matched. Carries nothing of the credential itself. @param rule the gitleaks
 * rule id @param anchored whether the rule matched a provider-stamped literal rather than a vendor name near a
 * random-looking string @param masked {@code ai.tessary.redaction.CredentialMasking#maskedKey} of the raw match; null
 * on older stamps, read as an unknown key
 */
public record RedactionStamp(
        String rule,
        String field,
        boolean anchored,
        @Nullable String masked) {

    /** The span's input, or its input messages. */
    public static final String INPUT = "input";

    /** The span's output, or its output messages. */
    public static final String OUTPUT = "output";

    public static final String ATTRIBUTES = "attributes";
}
