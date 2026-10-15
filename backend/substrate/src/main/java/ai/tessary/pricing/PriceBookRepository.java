// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The book in force for a source is its most recently published version. {@link #importBook} is one transaction: a
 * version row without its rates would make the next boot skip the book forever.
 */
@Repository
public class PriceBookRepository {

    private static final String BOOK_COLS = "version";

    private static final String RATE_COLS =
            "price_book_version, input_per_mtok, output_per_mtok, cache_read_per_mtok, cache_write_per_mtok";

    private static final String INSERT_MODEL =
            "INSERT INTO model (id, provider, display_name) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING";

    private static final String INSERT_RATE = """
            INSERT INTO model_price (price_book_version, model_id, input_per_mtok, output_per_mtok,
                                     cache_read_per_mtok, cache_write_per_mtok)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (price_book_version, model_id) DO NOTHING
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public PriceBookRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** What one {@link #importBook} call actually wrote. {@code applied=false} means the book was already in. */
    public record Imported(boolean applied, int newModels, int rates) {

        static final Imported SKIPPED = new Imported(false, 0, 0);
    }

    public boolean hasBook(String version) {
        return jdbc.sql("SELECT 1 FROM price_book WHERE version = :version")
                .param("version", version)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** Whether a book imported from a file with this full sha256 is already here, under any version. */
    public boolean hasDigest(String digest) {
        return jdbc.sql("SELECT 1 FROM price_book WHERE digest = :digest")
                .param("digest", digest)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** Null when there is no book or it predates the {@code digest} column. */
    public @Nullable String currentDigest(String source) {
        return jdbc.sql("SELECT digest FROM price_book WHERE source = :source "
                        + "ORDER BY published_at DESC, created_at DESC LIMIT 1")
                .param("source", source)
                .query((rs, n) -> rs.getString("digest"))
                .optional()
                .orElse(null);
    }

    /**
     * Fill a missing {@code digest} and move {@code published_at} earlier when needed: older boots dated the bundled
     * book by import time, which could outrank a newer fetched book. Only ever moves earlier, so it is idempotent.
     */
    public void reconcile(String version, String digest, Instant publishedAt) {
        jdbc.sql("UPDATE price_book SET digest = COALESCE(digest, :digest), "
                        + "published_at = LEAST(published_at, CAST(:published AS timestamptz)) "
                        + "WHERE version = :version")
                .param("version", version)
                .param("digest", digest)
                .param("published", publishedAt.toString())
                .update();
    }

    public boolean hasModel(String modelId) {
        return jdbc.sql("SELECT 1 FROM model WHERE id = :id")
                .param("id", modelId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * {@code publishedAt} decides which book is in force, so it must be the content's publish time, not import time.
     * The version insert's {@code ON CONFLICT DO NOTHING} is the gate when two instances boot at once.
     */
    @Transactional
    public Imported importBook(PriceSnapshot snapshot, Instant publishedAt) {
        int book = jdbc.sql("""
                        INSERT INTO price_book (version, source, published_at, digest)
                        VALUES (:version, :source, CAST(:published AS timestamptz), :digest)
                        ON CONFLICT (version) DO NOTHING
                        """)
                .param("version", snapshot.version())
                .param("source", snapshot.source())
                .param("digest", snapshot.digest())
                .param("published", publishedAt.toString())
                .update();
        if (book == 0) return Imported.SKIPPED;
        return new Imported(true, insertModels(snapshot.models()), insertRates(snapshot));
    }

    private int insertModels(List<PriceSnapshot.Model> models) {
        return batch(INSERT_MODEL, models.size(), (ps, i) -> {
            PriceSnapshot.Model m = models.get(i);
            ps.setString(1, m.id());
            ps.setString(2, m.provider());
            ps.setString(3, m.displayName());
        });
    }

    private int insertRates(PriceSnapshot snapshot) {
        List<PriceSnapshot.Model> models = snapshot.models();
        return batch(INSERT_RATE, models.size(), (ps, i) -> {
            PriceSnapshot.Model m = models.get(i);
            ModelRates r = m.rates();
            ps.setString(1, snapshot.version());
            ps.setString(2, m.id());
            ps.setBigDecimal(3, r.inputPerMtok());
            ps.setBigDecimal(4, r.outputPerMtok());
            ps.setBigDecimal(5, r.cacheReadPerMtok());
            ps.setBigDecimal(6, r.cacheWritePerMtok());
        });
    }

    /** One row per source, {@code manual} first. Older books stay so history priced against them keeps its meaning. */
    public List<PriceBook> currentBooks() {
        return jdbc.sql("SELECT DISTINCT ON (source) " + BOOK_COLS + " FROM price_book "
                        + "ORDER BY source, published_at DESC, created_at DESC")
                .query((rs, n) -> book(rs))
                .list();
    }

    /** Empty when no book in force holds a rate, which makes a span unpriced rather than free. */
    public Optional<ModelRate> rateFor(String modelId) {
        for (PriceBook book : currentBooks()) {
            Optional<ModelRate> rate = rateIn(book.version(), modelId);
            if (rate.isPresent()) return rate;
        }
        return Optional.empty();
    }

    /** Shown on the vitals card beside the unpriced count. */
    public int pricedModelCount() {
        List<PriceBook> books = currentBooks();
        if (books.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT count(DISTINCT model_id) FROM model_price WHERE price_book_version IN (:versions)")
                .param("versions", books.stream().map(PriceBook::version).toList())
                .query(Integer.class)
                .single();
    }

    /** This model's rates in one named book, ignoring layering — the audit read for a stamped version. */
    public Optional<ModelRate> rateIn(String priceBookVersion, String modelId) {
        return jdbc.sql("SELECT " + RATE_COLS + " FROM model_price "
                        + "WHERE price_book_version = :version AND model_id = :id")
                .param("version", priceBookVersion)
                .param("id", modelId)
                .query((rs, n) -> rate(rs))
                .optional();
    }

    private int batch(String sql, int size, RowBinder binder) {
        if (size == 0) return 0;
        int[] applied = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                binder.bind(ps, i);
            }

            @Override
            public int getBatchSize() {
                return size;
            }
        });
        int written = 0;
        for (int rows : applied) {
            if (rows > 0) written += rows;
        }
        return written;
    }

    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement ps, int index) throws SQLException;
    }

    private static PriceBook book(ResultSet rs) throws SQLException {
        return new PriceBook(rs.getString("version"));
    }

    private static ModelRate rate(ResultSet rs) throws SQLException {
        return new ModelRate(
                rs.getString("price_book_version"),
                new ModelRates(
                        rs.getBigDecimal("input_per_mtok"),
                        rs.getBigDecimal("output_per_mtok"),
                        rs.getBigDecimal("cache_read_per_mtok"),
                        rs.getBigDecimal("cache_write_per_mtok")));
    }
}
