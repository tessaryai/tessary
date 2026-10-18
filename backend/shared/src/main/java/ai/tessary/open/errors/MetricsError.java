// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import org.springframework.http.HttpStatus;

public enum MetricsError implements ErrorCode {
    UNKNOWN_VIEW(HttpStatus.BAD_REQUEST, "No metrics view named '%s'. Views: traces, llm_calls, tool_calls, cases"),
    UNKNOWN_MEASURE(HttpStatus.BAD_REQUEST, "View '%s' has no measure named '%s'"),
    UNKNOWN_DIMENSION(HttpStatus.BAD_REQUEST, "View '%s' has no dimension named '%s'"),
    UNKNOWN_FILTER_FIELD(HttpStatus.BAD_REQUEST, "View '%s' has no dimension named '%s' to filter on"),
    UNKNOWN_GRANULARITY(HttpStatus.BAD_REQUEST, "Granularity '%s' is not one of auto, hour, day, week"),
    BAD_OPERATOR(HttpStatus.BAD_REQUEST, "Filter operator '%s' is not supported; use in or not_in"),
    TOO_MANY_MEASURES(HttpStatus.BAD_REQUEST, "A query takes 1 to %s measures"),
    TOO_MANY_FILTERS(HttpStatus.BAD_REQUEST, "A query takes at most %s filters"),
    TOO_MANY_VALUES(HttpStatus.BAD_REQUEST, "A filter takes 1 to %s values"),
    INVALID_RANGE(HttpStatus.BAD_REQUEST, "'from' and 'to' must be ISO-8601 instants with from before to"),
    TOP_N_TOO_LARGE(HttpStatus.BAD_REQUEST, "top_n must be between 1 and %s for this query"),
    COMPARE_NEEDS_NO_AXIS(HttpStatus.BAD_REQUEST, "compare works only without a granularity"),
    INVALID_WIDGET(HttpStatus.BAD_REQUEST, "Widget '%s': %s"),
    TOO_MANY_WIDGETS(HttpStatus.BAD_REQUEST, "A dashboard holds at most %s widgets"),
    TOO_MANY_DASHBOARDS(HttpStatus.BAD_REQUEST, "A project holds at most %s dashboards"),
    WINDOW_TOO_LONG(HttpStatus.UNPROCESSABLE_ENTITY, "The window is longer than %s days; narrow it"),
    WINDOW_TOO_FINE(HttpStatus.UNPROCESSABLE_ENTITY, "Hour buckets cover at most %s days; pick day or week"),
    TIMED_OUT(HttpStatus.UNPROCESSABLE_ENTITY, "Query took too long. Narrow the window or pick a coarser granularity."),
    BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Metrics is busy. Other reads are running; try again in a moment."),
    WRONG_KEY_SCOPE(HttpStatus.FORBIDDEN, "This API key is not scoped for metrics (needs a query or admin key)"),
    SESSION_REQUIRED(HttpStatus.FORBIDDEN, "Dashboards are edited from a signed-in session, not an API key"),
    DASHBOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "No dashboard '%s' for this project"),
    BUILT_IN_READ_ONLY(HttpStatus.CONFLICT, "The default dashboard is built in; make a copy to edit it"),
    DUPLICATE_NAME(HttpStatus.CONFLICT, "A dashboard named '%s' already exists in this project");

    private final HttpStatus status;
    private final String template;

    MetricsError(HttpStatus status, String template) {
        this.status = status;
        this.template = template;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String template() {
        return template;
    }

    @Override
    public Class<? extends Enum<?>> declaringClass() {
        return MetricsError.class;
    }
}
