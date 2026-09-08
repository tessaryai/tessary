// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.errors;

import org.jspecify.annotations.Nullable;

public class EvalsException extends RuntimeException {

    private final ErrorCode error;
    private final transient @Nullable Object[] args;

    public EvalsException(ErrorCode error, @Nullable Object... args) {
        super(error.render(args));
        this.error = error;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public EvalsException(ErrorCode error, @Nullable Throwable cause, @Nullable Object... args) {
        super(error.render(args), cause);
        this.error = error;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public ErrorCode error() {
        return error;
    }

    public @Nullable Object[] args() {
        return args.clone();
    }
}
