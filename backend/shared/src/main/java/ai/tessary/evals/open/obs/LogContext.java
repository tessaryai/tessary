// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.obs;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Try-with-resources scope that binds business identifiers (runId, projectId,
 * graderId, …) to the SLF4J MDC so every log statement inside the scope carries
 * them as structured fields — without threading the ids through each message.
 *
 * <p>Async background jobs start with an empty MDC (they are dispatched off the
 * request thread, so there is no request MDC to inherit), so the scope is opened
 * fresh at the top of each async job worker (e.g. {@code SynthWorker},
 * {@code ClassifierWorker}). The
 * keys defined here are the single source of truth referenced by
 * {@code logback-spring.xml} ({@code includeMdcKeyName} +
 * {@code captureMdcAttributes}).
 *
 * <p>{@link #close()} restores the prior values rather than clearing, so nested
 * scopes compose and a pooled thread (see {@code MdcTaskDecorator}) never leaks
 * one run's context into the next.
 */
public final class LogContext implements AutoCloseable {

    public static final String RUN_ID = "runId";
    public static final String PROJECT_ID = "projectId";
    public static final String ORG_ID = "orgId";
    public static final String GRADER_ID = "graderId";
    public static final String JOB_ID = "jobId";
    public static final String CLASSIFIER_KEY = "classifierKey";
    public static final String CALL_SITE_ID = "callSiteId";
    /** The vendor/external trace id being processed by a trace-path worker. */
    public static final String SUBJECT_TRACE_ID = "subjectTraceId";
    /** Matches the key Micrometer/OTel tracing already binds on an HTTP request thread. */
    public static final String TRACE_ID = "traceId";
    /** Matches the key Micrometer/OTel tracing already binds on an HTTP request thread. */
    public static final String SPAN_ID = "spanId";

    private final Map<String, String> prior;

    private LogContext(Map<String, String> prior) {
        this.prior = prior;
    }

    /** Open a scope binding the given key/value pairs. Null values are skipped. */
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

    /** Convenience for the run path. Any of the ids may be null (skipped). */
    public static LogContext forRun(@Nullable String runId, @Nullable String projectId, @Nullable String orgId) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put(RUN_ID, runId);
        kv.put(PROJECT_ID, projectId);
        kv.put(ORG_ID, orgId);
        return put(kv);
    }

    /** Open a scope binding a single key. Null value is skipped. */
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
