// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.FindingEvidenceRepository.SpanRef;
import ai.tessary.classifier.finding.FindingEvidenceRow.Role;
import ai.tessary.classifier.metric.MetricBaselineRow.Measure;
import ai.tessary.rca.EvidenceSides.Side;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@code checks.md}: what code can compute from a finding's evidence rows before the agent reads any of them. Pure:
 * rows in, markdown out.
 */
final class EvidenceChecks {

    static final int ROW_CEILING = 20_000;

    /**
     * Its own ceiling and note rather than the dossier's, whose note sends the agent to page evidence: these tables are
     * computed, not paged.
     */
    static final int CHAR_CEILING = 40_000;

    private static final int TOP_VALUES = 8;
    private static final int MAX_HOURLY_BUCKETS = 48;
    private static final String NONE = "(none)";
    private static final String NO_VALUE = "—";

    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);

    private EvidenceChecks() {}

    /** The measure a shift moved; the other shapes ignore it. */
    enum Metric {
        LATENCY_MS(" ms"),
        TOTAL_COST(" USD"),
        INPUT_TOKENS(" tokens"),
        OUTPUT_TOKENS(" tokens"),
        CACHE_READ_TOKENS(" tokens"),
        CACHE_WRITE_TOKENS(" tokens");

        private final String unit;

        Metric(String unit) {
            this.unit = unit;
        }

        static Metric of(@Nullable String measure) {
            if (measure == null) return LATENCY_MS;
            return switch (measure) {
                case Measure.COST -> TOTAL_COST;
                case Measure.TOK_INPUT -> INPUT_TOKENS;
                case Measure.TOK_OUTPUT -> OUTPUT_TOKENS;
                case Measure.TOK_CACHE_READ -> CACHE_READ_TOKENS;
                case Measure.TOK_CACHE_WRITE -> CACHE_WRITE_TOKENS;
                default -> LATENCY_MS;
            };
        }

        /** The token buckets are not on the evidence row yet, so a token shift reads as having no values. */
        @Nullable
        Double value(SpanRef row) {
            return switch (this) {
                case LATENCY_MS -> {
                    Long ms = row.latencyMs();
                    yield ms == null ? null : ms.doubleValue();
                }
                case TOTAL_COST -> row.totalCost();
                case INPUT_TOKENS, OUTPUT_TOKENS, CACHE_READ_TOKENS, CACHE_WRITE_TOKENS -> null;
            };
        }

        String format(double value) {
            return this == TOTAL_COST
                    ? String.format(Locale.ROOT, "%.4f", value)
                    : String.format(Locale.ROOT, "%,d", Math.round(value));
        }
    }

    private record Field(String name, Function<SpanRef, @Nullable String> value) {}

    /**
     * Every field a row carries that two sides can differ on, in table order. Ids, ranks, times and previews are left
     * out; a shift leaves out its own measure, which is the series.
     */
    private static List<Field> fields(ClaimShape shape, Metric metric) {
        boolean shift = shape == ClaimShape.DISTRIBUTION_SHIFT;
        List<Field> fields = new ArrayList<>(List.of(
                new Field("name", SpanRef::name),
                new Field("kind", SpanRef::kind),
                new Field("status", SpanRef::status),
                new Field("level", SpanRef::level),
                new Field("errorType", SpanRef::errorType),
                new Field("callSiteId", SpanRef::callSiteId),
                new Field("models", row -> row.models().isEmpty() ? null : String.join(",", row.models()))));
        if (!(shift && metric == Metric.LATENCY_MS)) fields.add(banded("latencyMs", SpanRef::latencyMs));
        fields.add(banded("totalTokens", SpanRef::totalTokens));
        if (shift && metric != Metric.TOTAL_COST) {
            fields.add(new Field("totalCostCents", row -> {
                Double cost = row.totalCost();
                return cost == null ? null : band(Math.round(cost * 100));
            }));
        }
        return fields;
    }

    private static Field banded(String name, Function<SpanRef, @Nullable Long> value) {
        return new Field(name, row -> {
            Long v = value.apply(row);
            return v == null ? null : band(v);
        });
    }

    /** Powers of two, with 1 its own band: a first-turn burst is a failure pattern of its own. */
    static String band(long value) {
        if (value <= 0) return "0";
        long low = Long.highestOneBit(value);
        long high = low * 2 - 1;
        return low == high ? String.format(Locale.ROOT, "%,d", low) : String.format(Locale.ROOT, "%,d-%,d", low, high);
    }

    static String render(ClaimShape shape, Metric metric, List<SpanRef> rows, EvidenceSides sides, int recordedRows) {
        EvidenceSides.Split split = sides.split(rows);
        List<Dist> dists = new ArrayList<>();
        for (Field field : fields(shape, metric)) dists.add(Dist.of(field, split));
        List<String> sections = new ArrayList<>();
        sections.add(header(shape, rows, sides, split, recordedRows));
        sections.add(series(shape, metric, rows, sides, split));
        sections.add(sides(split, dists));
        sections.add(mix(shape, metric, split, dists));
        return truncate(String.join("\n\n", sections) + "\n");
    }

    private static String header(
            ClaimShape shape, List<SpanRef> rows, EvidenceSides sides, EvidenceSides.Split split, int recordedRows) {
        Set<String> flaggedKeys = new HashSet<>();
        for (SpanRef row : split.flagged()) flaggedKeys.add(EvidenceSides.key(row));
        StringBuilder sb = new StringBuilder("# Checks\n\n");
        sb.append("Computed in code over this finding's evidence rows only. Shape: ")
                .append(shape.label)
                .append(".\n");
        sb.append("Flagged rows: ")
                .append(roleCounts(rows, sides.roles(Side.FLAGGED), Set.of(), false))
                .append(". Comparison rows: ");
        if (split.comparison().isEmpty()) {
            sb.append("none. No comparison side: ")
                    .append(
                            shape == ClaimShape.PRESENCE_EVENT
                                    ? "a presence event has nothing it is compared against."
                                    : "the evidence holds no row this shape compares against.");
        } else {
            sb.append(roleCounts(rows, sides.roles(Side.COMPARISON), flaggedKeys, sides.anyWitness()))
                    .append('.');
        }
        sb.append(String.format(Locale.ROOT, "\nRows read: %,d of %,d recorded.", rows.size(), recordedRows));
        if (rows.size() >= ROW_CEILING && recordedRows > rows.size()) {
            int flaggedRecorded = 0;
            for (String role : sides.roles(Side.FLAGGED))
                flaggedRecorded += sides.roleCounts().getOrDefault(role, 0);
            long flaggedRead = rows.stream()
                    .filter(row -> sides.sideOf(row.role()) == Side.FLAGGED)
                    .count();
            sb.append(String.format(
                    Locale.ROOT,
                    "\nRow ceiling: %,d of %,d recorded rows read; %s. The tables below are over the rows read.",
                    rows.size(),
                    recordedRows,
                    flaggedRead < flaggedRecorded
                            ? "the flagged side itself was cut"
                            : "flagged roles were read first and are whole"));
        }
        return sb.toString();
    }

    /** Distinct rows per role; a comparison role leaves out rows that are flagged under another role. */
    private static String roleCounts(List<SpanRef> rows, List<String> roles, Set<String> exclude, boolean unflagged) {
        List<String> parts = new ArrayList<>();
        for (String role : roles) {
            Set<String> keys = new HashSet<>();
            for (SpanRef row : rows) {
                String key = EvidenceSides.key(row);
                if (role.equals(row.role()) && !exclude.contains(key)) keys.add(key);
            }
            String prefix = unflagged && Role.MEMBER.equals(role) ? "unflagged " : "";
            parts.add(String.format(Locale.ROOT, "%s`%s` (%,d)", prefix, role, keys.size()));
        }
        return String.join(", ", parts);
    }

    private record Timed(Instant at, SpanRef row, boolean flagged) {}

    private record Point(int bucket, double value) {}

    private static String series(
            ClaimShape shape, Metric metric, List<SpanRef> rows, EvidenceSides sides, EvidenceSides.Split split) {
        boolean comparisonSide = !split.comparison().isEmpty();
        List<Timed> timed = new ArrayList<>();
        int untimed = 0;
        for (SpanRef row : split.flagged()) {
            Instant at = instant(row.startedAt());
            if (at == null) untimed++;
            else timed.add(new Timed(at, row, true));
        }
        for (SpanRef row : split.comparison()) {
            Instant at = instant(row.startedAt());
            if (at == null) untimed++;
            else timed.add(new Timed(at, row, false));
        }
        List<String> blocks = new ArrayList<>();
        String untimedLine = untimed == 0
                ? ""
                : untimed == 1
                        ? "\n1 row has no startedAt and is not in the series."
                        : String.format(
                                Locale.ROOT, "\n%,d rows have no startedAt and are not in the series.", untimed);
        if (timed.isEmpty()) {
            blocks.add("No row has a startedAt; there is no series." + untimedLine);
            blocks.add("Change point: not computed (fewer than two buckets).");
            return section("## Series", blocks);
        }

        Instant min =
                timed.stream().map(Timed::at).min(Comparator.naturalOrder()).orElseThrow();
        Instant max =
                timed.stream().map(Timed::at).max(Comparator.naturalOrder()).orElseThrow();
        ChronoUnit unit = ChronoUnit.HOURS;
        if (ChronoUnit.HOURS.between(min.truncatedTo(unit), max.truncatedTo(unit)) + 1 > MAX_HOURLY_BUCKETS) {
            unit = ChronoUnit.DAYS;
        }
        Instant start = min.truncatedTo(unit);
        int buckets = (int) unit.between(start, max.truncatedTo(unit)) + 1;
        Duration width = unit.getDuration();
        DateTimeFormatter label = unit == ChronoUnit.HOURS ? HOUR : DAY;

        int[] flagged = new int[buckets];
        int[] comparison = new int[buckets];
        List<List<Double>> values = new ArrayList<>();
        for (int b = 0; b < buckets; b++) values.add(new ArrayList<>());
        for (Timed t : timed) {
            int b = (int) unit.between(start, t.at().truncatedTo(unit));
            if (t.flagged()) flagged[b]++;
            else comparison[b]++;
            Double v = metric.value(t.row());
            if (v != null) values.get(b).add(v);
        }

        blocks.add(String.format(
                        Locale.ROOT,
                        "%s buckets, %s to %s (%,d %s).",
                        unit == ChronoUnit.HOURS ? "Hourly" : "Daily",
                        label.format(start),
                        label.format(start.plus(width.multipliedBy(buckets - 1L))),
                        buckets,
                        buckets == 1 ? "bucket" : "buckets")
                + untimedLine);

        StringBuilder table = new StringBuilder();
        List<Point> points = new ArrayList<>();
        if (shape == ClaimShape.DISTRIBUTION_SHIFT) {
            table.append("| bucket | rows | mean | p50 |\n|---|---|---|---|");
            for (int b = 0; b < buckets; b++) {
                List<Double> vs = values.get(b);
                String mean = vs.isEmpty() ? "" : metric.format(mean(vs));
                String p50 = vs.isEmpty() ? "" : metric.format(p50(vs));
                table.append(String.format(
                        Locale.ROOT,
                        "\n| %s | %,d | %s | %s |",
                        label.format(start.plus(width.multipliedBy(b))),
                        vs.size(),
                        mean,
                        p50));
                if (!vs.isEmpty()) points.add(new Point(b, mean(vs)));
            }
        } else if (shape == ClaimShape.RATE_RISE && comparisonSide) {
            table.append("| bucket | flagged | comparison | flagged share |\n|---|---|---|---|");
            for (int b = 0; b < buckets; b++) {
                int total = flagged[b] + comparison[b];
                table.append(String.format(
                        Locale.ROOT,
                        "\n| %s | %,d | %,d | %s |",
                        label.format(start.plus(width.multipliedBy(b))),
                        flagged[b],
                        comparison[b],
                        total == 0 ? "" : percent(flagged[b], total)));
                if (total > 0) points.add(new Point(b, (double) flagged[b] / total));
            }
        } else {
            table.append("| bucket | flagged |\n|---|---|");
            for (int b = 0; b < buckets; b++) {
                table.append(String.format(
                        Locale.ROOT, "\n| %s | %,d |", label.format(start.plus(width.multipliedBy(b))), flagged[b]));
                points.add(new Point(b, flagged[b]));
            }
        }
        blocks.add(table.toString());

        Integer detected = null;
        Instant earliest = null;
        for (SpanRef row : rows) {
            if (!Role.CHANGEPOINT.equals(row.role()) || sides.sideOf(row.role()) == Side.NONE) continue;
            Instant at = instant(row.startedAt());
            if (at != null && !at.isBefore(start) && (earliest == null || at.isBefore(earliest))) earliest = at;
        }
        if (earliest != null) {
            int b = (int) unit.between(start, earliest.truncatedTo(unit));
            if (b < buckets) detected = b;
        }
        int changeBucket;
        String changeLine;
        if (detected != null) {
            changeBucket = detected;
            changeLine = "Change point: %s, from the detector's changepoint rows.";
        } else if (points.size() >= 2) {
            changeBucket = bestSplit(points);
            changeLine = "Change point: %s, computed as the best single split of the series.";
        } else {
            blocks.add("Change point: not computed (fewer than two buckets).");
            return section("## Series", blocks);
        }
        String changeAt = label.format(start.plus(width.multipliedBy(changeBucket)));
        blocks.add(String.format(Locale.ROOT, changeLine, changeAt) + "\n"
                + beforeAfter(shape, metric, comparisonSide, changeBucket, buckets, flagged, comparison, values));
        return section("## Series", blocks);
    }

    /**
     * The k maximising the mean step weighted by sqrt((k-1)(n-k+1)/n), so a split near either end needs a larger
     * step. Ties go to the earliest.
     */
    private static int bestSplit(List<Point> points) {
        int n = points.size();
        int best = 1;
        double bestScore = -1;
        for (int k = 1; k < n; k++) {
            double before = 0;
            double after = 0;
            for (int i = 0; i < k; i++) before += points.get(i).value();
            for (int i = k; i < n; i++) after += points.get(i).value();
            double score = Math.abs(after / (n - k) - before / k) * Math.sqrt((double) k * (n - k) / n);
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        return points.get(best).bucket();
    }

    private static String beforeAfter(
            ClaimShape shape,
            Metric metric,
            boolean comparisonSide,
            int changeBucket,
            int buckets,
            int[] flagged,
            int[] comparison,
            List<List<Double>> values) {
        List<String> halves = new ArrayList<>();
        for (boolean before : List.of(true, false)) {
            int from = before ? 0 : changeBucket;
            int to = before ? changeBucket : buckets;
            String name = before ? "Before" : "After";
            if (shape == ClaimShape.DISTRIBUTION_SHIFT) {
                List<Double> vs = new ArrayList<>();
                for (int b = from; b < to; b++) vs.addAll(values.get(b));
                halves.add(
                        vs.isEmpty()
                                ? name + ": no rows."
                                : String.format(
                                        Locale.ROOT,
                                        "%s: mean %s%s over %,d %s.",
                                        name,
                                        metric.format(mean(vs)),
                                        metric.unit,
                                        vs.size(),
                                        vs.size() == 1 ? "row" : "rows"));
            } else if (shape == ClaimShape.RATE_RISE && comparisonSide) {
                int f = 0;
                int total = 0;
                for (int b = from; b < to; b++) {
                    f += flagged[b];
                    total += flagged[b] + comparison[b];
                }
                halves.add(
                        total == 0
                                ? name + ": no rows."
                                : String.format(
                                        Locale.ROOT,
                                        "%s: share %s over %,d %s.",
                                        name,
                                        percent(f, total),
                                        total,
                                        total == 1 ? "row" : "rows"));
            } else {
                int f = 0;
                for (int b = from; b < to; b++) f += flagged[b];
                halves.add(String.format(
                        Locale.ROOT,
                        "%s: %,d flagged over %,d %s.",
                        name,
                        f,
                        to - from,
                        to - from == 1 ? "bucket" : "buckets"));
            }
        }
        return String.join(" ", halves);
    }

    /** One field's values on each side, ordered by how much the sides differ. */
    private record Dist(
            Field field,
            Map<String, Integer> flagged,
            Map<String, Integer> comparison,
            int flaggedRows,
            int comparisonRows,
            List<String> values) {

        static Dist of(Field field, EvidenceSides.Split split) {
            Map<String, Integer> flagged = count(field, split.flagged());
            Map<String, Integer> comparison = count(field, split.comparison());
            List<String> values = new ArrayList<>(flagged.keySet());
            for (String v : comparison.keySet()) if (!flagged.containsKey(v)) values.add(v);
            Dist dist = new Dist(
                    field,
                    flagged,
                    comparison,
                    split.flagged().size(),
                    split.comparison().size(),
                    values);
            Comparator<String> order = split.comparison().isEmpty()
                    ? Comparator.comparingInt((String v) -> -dist.flagged(v))
                    : Comparator.comparingLong((String v) -> -Math.abs(dist.gap(v)));
            values.sort(order.thenComparing(Comparator.naturalOrder()));
            return dist;
        }

        private static Map<String, Integer> count(Field field, List<SpanRef> rows) {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (SpanRef row : rows) {
                String v = field.value().apply(row);
                out.merge(v == null ? NONE : v, 1, Integer::sum);
            }
            return out;
        }

        int flagged(String value) {
            return flagged.getOrDefault(value, 0);
        }

        int comparison(String value) {
            return comparison.getOrDefault(value, 0);
        }

        /** Shares compared over a common denominator, so equal shares are exactly equal. */
        long gap(String value) {
            return (long) flagged(value) * comparisonRows - (long) comparison(value) * flaggedRows;
        }

        boolean recorded() {
            return values.stream().anyMatch(v -> !NONE.equals(v));
        }

        boolean differs() {
            return values.stream().anyMatch(v -> gap(v) != 0);
        }

        List<String> top() {
            return values.subList(0, Math.min(TOP_VALUES, values.size()));
        }

        String more() {
            int more = values.size() - TOP_VALUES;
            if (more <= 0) return "";
            return String.format(Locale.ROOT, "\n(%,d more %s)", more, more == 1 ? "value" : "values");
        }
    }

    private static String sides(EvidenceSides.Split split, List<Dist> dists) {
        boolean comparisonSide = !split.comparison().isEmpty();
        List<String> blocks = new ArrayList<>();
        List<String> notRecorded = new ArrayList<>();
        List<String> noDifference = new ArrayList<>();
        for (Dist dist : dists) {
            if (!dist.recorded()) {
                notRecorded.add(dist.field().name());
            } else if (!comparisonSide) {
                StringBuilder table =
                        new StringBuilder("### " + dist.field().name() + "\n| value | flagged |\n|---|---|");
                for (String v : dist.top()) {
                    table.append("\n| ")
                            .append(cell(v))
                            .append(" | ")
                            .append(countShare(dist.flagged(v), dist.flaggedRows()))
                            .append(" |");
                }
                blocks.add(table + dist.more());
            } else if (!dist.differs()) {
                noDifference.add(dist.field().name());
            } else {
                StringBuilder table = new StringBuilder("### " + dist.field().name()
                        + "\n| value | flagged | comparison | difference |\n|---|---|---|---|");
                for (String v : dist.top()) {
                    table.append("\n| ")
                            .append(cell(v))
                            .append(" | ")
                            .append(countShare(dist.flagged(v), dist.flaggedRows()))
                            .append(" | ")
                            .append(countShare(dist.comparison(v), dist.comparisonRows()))
                            .append(" | ")
                            .append(difference(dist, v))
                            .append(" |");
                }
                blocks.add(table + dist.more());
            }
        }
        if (blocks.isEmpty()) blocks.add("No field differs between the sides.");
        List<String> lines = new ArrayList<>();
        if (!notRecorded.isEmpty()) lines.add("Not recorded on these rows: " + String.join(", ", notRecorded) + ".");
        if (!noDifference.isEmpty()) {
            lines.add("No difference between the sides: " + String.join(", ", noDifference) + ".");
        }
        if (!lines.isEmpty()) blocks.add(String.join("\n", lines));
        return section(comparisonSide ? "## Flagged against comparison" : "## Flagged rows alone", blocks);
    }

    private static String mix(ClaimShape shape, Metric metric, EvidenceSides.Split split, List<Dist> dists) {
        List<String> blocks = new ArrayList<>();
        if (shape == ClaimShape.PRESENCE_EVENT || split.comparison().isEmpty()) {
            blocks.add("No comparison side.");
            return section("## Mix within groups", blocks);
        }
        List<Dist> listed =
                dists.stream().filter(d -> d.recorded() && d.differs()).toList();
        if (listed.isEmpty()) {
            blocks.add("No field differs between the sides.");
            return section("## Mix within groups", blocks);
        }
        boolean shift = shape == ClaimShape.DISTRIBUTION_SHIFT;
        if (!shift) {
            int flagged = split.flagged().size();
            blocks.add("Overall flagged share: "
                    + percent(flagged, flagged + split.comparison().size()) + ".");
        }
        for (Dist dist : listed) {
            StringBuilder table = new StringBuilder("### " + dist.field().name());
            table.append(
                    shift
                            ? "\n| value | flagged mean | comparison mean | ratio |\n|---|---|---|---|"
                            : "\n| value | flagged | comparison | flagged share |\n|---|---|---|---|");
            for (String v : dist.top()) {
                table.append("\n| ").append(cell(v)).append(" | ");
                if (shift) {
                    Double f = groupMean(dist.field(), v, split.flagged(), metric);
                    Double c = groupMean(dist.field(), v, split.comparison(), metric);
                    table.append(f == null ? NO_VALUE : metric.format(f))
                            .append(" | ")
                            .append(c == null ? NO_VALUE : metric.format(c))
                            .append(" | ")
                            .append(
                                    f == null || c == null || c == 0
                                            ? NO_VALUE
                                            : String.format(Locale.ROOT, "%.2f", f / c));
                } else {
                    int f = dist.flagged(v);
                    int c = dist.comparison(v);
                    table.append(String.format(Locale.ROOT, "%,d | %,d | %s", f, c, percent(f, f + c)));
                }
                table.append(" |");
            }
            blocks.add(table.toString());
        }
        return section("## Mix within groups", blocks);
    }

    private static @Nullable Double groupMean(Field field, String value, List<SpanRef> rows, Metric metric) {
        List<Double> vs = new ArrayList<>();
        for (SpanRef row : rows) {
            String v = field.value().apply(row);
            Double m = metric.value(row);
            if (value.equals(v == null ? NONE : v) && m != null) vs.add(m);
        }
        return vs.isEmpty() ? null : mean(vs);
    }

    /**
     * Cut on the last whole line that leaves room for the note. The note is sized at its widest, so the shown count
     * cannot push the file back over the ceiling.
     */
    static String truncate(String text) {
        if (text.length() <= CHAR_CEILING) return text;
        int limit = CHAR_CEILING - truncationNote(CHAR_CEILING, text.length()).length();
        int cut = text.lastIndexOf('\n', limit);
        if (cut < 0) cut = limit;
        return text.substring(0, cut) + truncationNote(cut, text.length());
    }

    private static String truncationNote(int shown, int total) {
        return String.format(
                Locale.ROOT,
                "\n\n[checks truncated: %,d of %,d characters shown. The series and change point above the cut are"
                        + " complete.]",
                shown,
                total);
    }

    private static String section(String heading, List<String> blocks) {
        return heading + "\n\n" + String.join("\n\n", blocks);
    }

    private static String difference(Dist dist, String value) {
        if (dist.gap(value) == 0) return "0.0 pp";
        double pp = 100.0 * dist.flagged(value) / dist.flaggedRows()
                - 100.0 * dist.comparison(value) / dist.comparisonRows();
        return String.format(Locale.ROOT, "%+.1f pp", pp);
    }

    private static String countShare(int count, int rows) {
        return String.format(Locale.ROOT, "%,d (%s)", count, percent(count, rows));
    }

    private static String percent(int part, int whole) {
        return String.format(Locale.ROOT, "%.1f%%", whole == 0 ? 0.0 : 100.0 * part / whole);
    }

    private static String cell(String value) {
        return value.replace('\n', ' ').replace("|", "\\|");
    }

    private static double mean(List<Double> values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    /** Nearest rank. */
    private static double p50(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((sorted.size() + 1) / 2 - 1);
    }

    private static @Nullable Instant instant(@Nullable String at) {
        if (at == null) return null;
        try {
            return Instant.parse(at);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
