// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

/**
 * The encoder could not be reached at all (refused, timed out, unresolved). The model is down, not broken, so
 * {@code ClassifierWorker} hands the job back without spending an attempt and the cursor stays put. A 5xx or 401 is a
 * real fault and still counts.
 */
public class EncoderUnreachableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public EncoderUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
