// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

/**
 * The encoder could not be reached at all: the connection was refused, timed out, closed before it
 * opened, or the host did not resolve. {@link LauncherEncoderScorer} throws it in place of a plain
 * {@link IllegalStateException} for exactly those transport failures.
 *
 * <p>It means the model is down, not broken: a GPU instance asleep between scheduled runs, or a
 * developer's model that is not running. {@code ClassifierWorker} hands the job back without spending
 * an attempt, so five in a row never dead-letter the sweep, and the cursor stays where the last page
 * landed. A model that answers with a 5xx or a 401 is a real fault and still counts.
 */
public class EncoderUnreachableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public EncoderUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
