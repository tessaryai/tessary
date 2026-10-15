// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the query API. {@code dataset}, {@code field} and {@code interval} resolve against
 * {@link QueryDataset}/{@link QueryInterval} allow-lists, never interpolated raw; filters are bound.
 */
public final class QueryDtos {

    private QueryDtos() {}

    /**
     * Half-open on the dataset's own event clock ({@link QueryDataset#timeColumn()}). Either bound may be null;
     * {@code timeseries()} requires both.
     */
    public record TimeRange(@Nullable String from, @Nullable String to) {}

    public record CountRequest(
            @NotBlank String dataset,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters) {}

    public record CountView(long count) {

        public static CountView of(long count) {
            return new CountView(count);
        }
    }

    public record TimeseriesRequest(
            @NotBlank String dataset,
            @NotBlank String interval,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters) {}

    /** {@code bucketStart} is the truncated ISO-8601 instant. */
    public record TimeseriesBucket(
            @JsonProperty("bucket_start") String bucketStart, long count) {}

    public record TimeseriesView(List<TimeseriesBucket> buckets) {

        public static TimeseriesView of(List<QueryRepository.Bucket> rows) {
            return new TimeseriesView(rows.stream()
                    .map(b -> new TimeseriesBucket(b.bucketStart(), b.count()))
                    .toList());
        }
    }

    public record FacetsRequest(
            @NotBlank String dataset,
            @NotBlank String field,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters,
            @JsonProperty("top_n") @Nullable Integer topN) {}

    /** {@code value} is null when the dimension is NULL. */
    public record FacetBucket(@Nullable String value, long count) {}

    public record FacetsView(String field, List<FacetBucket> facets) {

        public static FacetsView of(String field, List<QueryRepository.Facet> rows) {
            return new FacetsView(
                    field,
                    rows.stream()
                            .map(f -> new FacetBucket(f.value(), f.count()))
                            .toList());
        }
    }

    /**
     * {@code mode} must be {@code keyword}; anything else is {@code 400 UNKNOWN_SEARCH_MODE}. {@code cursor} is the
     * {@code next_cursor} token.
     */
    public record SearchRequest(
            @NotBlank String dataset,
            @Nullable String q,
            @Nullable String mode,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters,
            @Nullable Integer limit,
            @Nullable String cursor) {}

    public record SearchRow(
            String id, @JsonProperty("created_at") String createdAt, Map<String, String> fields) {}

    public record SearchView(
            List<SearchRow> rows,
            @JsonProperty("next_cursor") @Nullable String nextCursor) {

        public static SearchView of(QueryRepository.SearchPage page) {
            return new SearchView(
                    page.rows().stream()
                            .map(r -> new SearchRow(r.id(), r.createdAt(), r.fields()))
                            .toList(),
                    page.nextCursor());
        }
    }
}
