// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.jspecify.annotations.Nullable;

public class TessaryException extends RuntimeException {

    private final ErrorCode error;
    private final transient @Nullable Object[] args;

    public TessaryException(ErrorCode error, @Nullable Object... args) {
        super(error.render(args));
        this.error = error;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public TessaryException(ErrorCode error, @Nullable Throwable cause, @Nullable Object... args) {
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
