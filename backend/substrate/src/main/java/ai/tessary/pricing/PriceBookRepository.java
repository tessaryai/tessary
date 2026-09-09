// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pricing;

import ai.tessary.storage.Timestamps;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pricing schema — {@code model}, {@code price_book}, {@code model_price} — in one place.
 *
 * <p><b>The book in force for a source is its most recently published version.</b> {@link #currentBooks()}
 * returns one row per source — today just {@code litellm} — and {@link #rateFor} walks them in that order,
 * returning the first hit together with the version that produced it, because a writer has to stamp what
 * priced the row. A hand-maintained {@code manual} source used to be layered ahead of the vendored
 * one; every row it carried is now either reconciled upstream or a genuine gap priced as unpriced rather
 * than guessed, so this is single-source in practice, not by a hardcoded assumption.
 *
 * <p><b>Importing a book is one transaction.</b> {@link #importBook} inserts the version, the missing
 * model rows and the rates together or not at all. Half a book is the one state the importer could not
 * recover from on its own: the version row alone would make the next boot decide the book was already
 * present and skip it forever, leaving every model in it permanently unpriced.
 */
@Repository
public class PriceBookRepository {

    private static final String BOOK_COLS = "version, source, published_at";

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

    /** Whether the pricing schema knows this model id — the resolver's one question. */
    public boolean hasModel(String modelId) {
        return jdbc.sql("SELECT 1 FROM model WHERE id = :id")
                .param("id", modelId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Insert a snapshot as a new book: the version, any model ids not seen before, and its rates.
     *
     * <p>The version insert is the gate. Two instances booting at once both reach here; the second one's
     * {@code ON CONFLICT DO NOTHING} reports nothing inserted and it stops, rather than racing the first
     * through 2,000 rate rows to the same end.
     */
    @Transactional
    public Imported importBook(PriceSnapshot snapshot, Instant publishedAt) {
        int book = jdbc.sql("""
                        INSERT INTO price_book (version, source, published_at)
                        VALUES (:version, :source, CAST(:published AS timestamptz))
                        ON CONFLICT (version) DO NOTHING
                        """)
                .param("version", snapshot.version())
                .param("source", snapshot.source())
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

    /**
     * The books in force, most authoritative first: the newest {@code manual} book, then the newest book of
     * every other source. One row per source — an older snapshot stays in the table so history priced
     * against it keeps its meaning, but it never prices anything new.
     */
    public List<PriceBook> currentBooks() {
        return jdbc.sql("SELECT DISTINCT ON (source) " + BOOK_COLS + " FROM price_book "
                        + "ORDER BY source, published_at DESC, created_at DESC")
                .query((rs, n) -> book(rs))
                .list();
    }

    /**
     * This model's rates from the highest-precedence book that carries them, or empty when no book in force
     * holds a rate for it — which is what makes a span unpriced rather than free.
     */
    public Optional<ModelRate> rateFor(String modelId) {
        for (PriceBook book : currentBooks()) {
            Optional<ModelRate> rate = rateIn(book.version(), modelId);
            if (rate.isPresent()) return rate;
        }
        return Optional.empty();
    }

    /**
     * How many distinct models the books in force can price — the coverage figure the vitals card shows
     * beside its unpriced count, so a thin price book reads differently from a thin corpus.
     */
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
        return new PriceBook(
                rs.getString("version"),
                rs.getString("source"),
                Objects.requireNonNull(Timestamps.instant(rs, "published_at")));
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
