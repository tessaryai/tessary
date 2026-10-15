// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Try-with-resources scope binding business ids to the SLF4J MDC. Async jobs start with an empty MDC, so each worker
 * opens one. Keys are referenced by {@code logback-spring.xml}. {@link #close()} restores prior values rather than
 * clearing, so scopes nest and a pooled thread never leaks one run's context into the next.
 */
public final class LogContext implements AutoCloseable {

    public static final String PROJECT_ID = "projectId";
    public static final String JOB_ID = "jobId";
    public static final String CLASSIFIER_KEY = "classifierKey";
    /** Matches the key Micrometer/OTel tracing binds on a request thread. */
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";

    private final Map<String, String> prior;

    private LogContext(Map<String, String> prior) {
        this.prior = prior;
    }

    /** Null values are skipped. */
    public static LogContext put(Map<String, String> kv) {
        Map<String, String> prior = new HashMap<>();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            prior.put(e.getKey(), MDC.get(e.getKey()));
            MDC.put(e.getKey(), e.getValue());
        }
        return new LogContext(prior);
    }

    /** A null value is skipped. */
    public static LogContext with(String key, @Nullable String value) {
        Map<String, String> kv = new HashMap<>();
        kv.put(key, value);
        return put(kv);
    }

    @Override
    public void close() {
        for (Map.Entry<String, String> e : prior.entrySet()) {
            if (e.getValue() == null) {
                MDC.remove(e.getKey());
            } else {
                MDC.put(e.getKey(), e.getValue());
            }
        }
    }
}
