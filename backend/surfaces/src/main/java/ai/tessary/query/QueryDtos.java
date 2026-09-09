// SPDX-License-Identifier: Apache-2.0
package ai.tessary.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the aggregation-first query API. Snake_case on the wire, camelCase in Java
 * (the {@code ClassifierDtos} convention). All four operations share a common shape — a {@code dataset},
 * an optional time {@code range}, and equality {@code filters} on indexed discriminators — plus a few
 * per-operation extras. Filters are bound as parameters; the {@code dataset}/{@code field}/{@code
 * interval} strings are resolved against {@link QueryDataset}/{@link QueryInterval} allow-lists before
 * any SQL is built (never interpolated raw).
 */
public final class QueryDtos {

    private QueryDtos() {}

    /**
     * A half-open time window {@code [from, to)} on the dataset's {@code created_at} (ISO-8601 strings).
     * Either bound may be null (open-ended); {@code timeseries()} requires both.
     */
    public record TimeRange(@Nullable String from, @Nullable String to) {}

    /** {@code count()} request: total matching rows for a dataset/range/filters. */
    public record CountRequest(
            @NotBlank String dataset,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters) {}

    /** {@code count()} result: a single total. */
    public record CountView(long count) {

        public static CountView of(long count) {
            return new CountView(count);
        }
    }

    /** {@code timeseries()} request: bucketed counts over {@code created_at} at {@code interval}. */
    public record TimeseriesRequest(
            @NotBlank String dataset,
            @NotBlank String interval,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters) {}

    /** One {@code (bucket_start, count)} point. {@code bucketStart} is the truncated ISO-8601 instant. */
    public record TimeseriesBucket(
            @JsonProperty("bucket_start") String bucketStart, long count) {}

    /** {@code timeseries()} result: the ordered list of buckets. */
    public record TimeseriesView(List<TimeseriesBucket> buckets) {

        public static TimeseriesView of(List<QueryRepository.Bucket> rows) {
            return new TimeseriesView(rows.stream()
                    .map(b -> new TimeseriesBucket(b.bucketStart(), b.count()))
                    .toList());
        }
    }

    /** {@code facets()} request: top-N breakdown of {@code field} for a dataset/range/filters. */
    public record FacetsRequest(
            @NotBlank String dataset,
            @NotBlank String field,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters,
            @JsonProperty("top_n") @Nullable Integer topN) {}

    /** One {@code (value, count)} facet bucket. {@code value} is null when the dimension is NULL. */
    public record FacetBucket(@Nullable String value, long count) {}

    /** {@code facets()} result: the ranked facet buckets for {@code field}. */
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
     * {@code search()} request: structured/keyword filter search returning matching rows (not
     * aggregated). {@code mode} defaults to, and must be, {@code keyword} — a prior {@code semantic}
     * mode (kNN over a vector index) was removed along with the rest of the embedding substrate
     * (#1116); any other {@code mode} value is a {@code 400 UNKNOWN_SEARCH_MODE}. {@code cursor} is
     * the opaque keyset page token returned as {@code next_cursor}.
     */
    public record SearchRequest(
            @NotBlank String dataset,
            @Nullable String q,
            @Nullable String mode,
            @Nullable TimeRange range,
            @Nullable Map<String, String> filters,
            @Nullable Integer limit,
            @Nullable String cursor) {}

    /** One matched row: its id, {@code created_at}, and the dataset's allow-listed display columns. */
    public record SearchRow(
            String id, @JsonProperty("created_at") String createdAt, Map<String, String> fields) {}

    /** {@code search()} result: a bounded, keyset-paginated page of rows. */
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
