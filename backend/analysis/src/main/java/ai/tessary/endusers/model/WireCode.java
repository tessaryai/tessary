// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;

/** A Users view code: one fixed string on the wire, in the database and in the frontend words file. */
public interface WireCode {

    String wire();

    /** Exact match, no trimming or case folding: the wire form is also the stored form. */
    static <E extends Enum<E> & WireCode> E parse(Class<E> type, String value) {
        for (E code : type.getEnumConstants()) {
            if (code.wire().equals(value)) return code;
        }
        throw new TessaryException(CommonError.INVALID_PARAMETER, type.getSimpleName());
    }
}
