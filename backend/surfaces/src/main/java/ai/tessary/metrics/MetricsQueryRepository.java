// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.metrics.MetricsStatement.Column;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MetricsQueryRepository {

    private final JdbcClient jdbc;

    public MetricsQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One result row as SQL returned it; a measure SQL returned as NULL is null here. */
    public record Raw(
            @Nullable Instant bucket,
            @Nullable String key,
            long rank,
            long n,
            long totalKeys,
            Map<String, @Nullable Double> values) {}

    public record Result(List<Raw> rows, @Nullable List<Raw> previousRows) {}

    /**
     * One read transaction: the timeout, then the statement, then the previous-window statement when asked. Call it
     * through the Spring proxy only: a self-call skips the transaction, and the transaction-local timeout would then
     * bind to nothing.
     */
    @Transactional(readOnly = true)
    public Result run(MetricsStatement current, @Nullable MetricsStatement previous, long statementTimeoutMs) {
        jdbc.sql("SELECT set_config('statement_timeout', :ms, true)")
                .param("ms", Long.toString(statementTimeoutMs))
                .query()
                .listOfRows();
        List<Raw> rows = read(current);
        return new Result(rows, previous == null ? null : read(previous));
    }

    private List<Raw> read(MetricsStatement st) {
        return jdbc.sql(st.sql())
                .params(st.params())
                .query((rs, i) -> map(st, rs))
                .list();
    }

    private static Raw map(MetricsStatement st, ResultSet rs) throws SQLException {
        Instant bucket =
                st.shape().hasAxis() ? rs.getObject("bkt", OffsetDateTime.class).toInstant() : null;
        boolean grouped = st.shape().hasDimension();
        Map<String, @Nullable Double> values = new LinkedHashMap<>();
        for (Column c : st.columns()) {
            if (c.array()) {
                Array a = rs.getArray(c.alias());
                if (a == null) {
                    for (MetricsMeasure m : c.measures()) values.put(m.wire(), null);
                } else {
                    Double[] q = (Double[]) a.getArray();
                    for (MetricsMeasure m : c.measures()) {
                        values.put(m.wire(), q[Objects.requireNonNull(m.arrayIndex())]);
                    }
                }
            } else {
                double v = rs.getDouble(c.alias());
                values.put(c.measures().get(0).wire(), rs.wasNull() ? null : Double.valueOf(v));
            }
        }
        return new Raw(
                bucket,
                grouped ? rs.getString("k") : null,
                grouped ? rs.getLong("rank") : 1,
                rs.getLong("n"),
                grouped ? rs.getLong("total_keys") : 0,
                values);
    }
}
