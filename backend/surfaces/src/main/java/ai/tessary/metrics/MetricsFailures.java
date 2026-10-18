// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

public final class MetricsFailures {

    static final String QUERY_CANCELED = "57014";

    private MetricsFailures() {}

    /** A statement cancelled by its timeout becomes TIMED_OUT (cause kept); anything else comes back unchanged. */
    public static RuntimeException translate(DataAccessException failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return new TessaryException(MetricsError.TIMED_OUT, failure);
            }
        }
        return failure;
    }
}
