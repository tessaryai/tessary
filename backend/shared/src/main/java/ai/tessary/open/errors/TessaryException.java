// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.jspecify.annotations.Nullable;

public class TessaryException extends RuntimeException {

    private final ErrorCode error;

    public TessaryException(ErrorCode error, @Nullable Object... args) {
        super(error.render(args));
        this.error = error;
    }

    public TessaryException(ErrorCode error, @Nullable Throwable cause, @Nullable Object... args) {
        super(error.render(args), cause);
        this.error = error;
    }

    public ErrorCode error() {
        return error;
    }
}
