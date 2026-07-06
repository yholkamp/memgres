package com.memgres.select;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group 1 (mtask-8): {@code SelectExecutor}'s early column-validation loop over
 * {@code stmt.targets()} runs whenever a query has a JOIN and rejects any qualified
 * {@code ColumnRef} it can't find as a raw column, with no awareness of the attribute-notation
 * fallback ({@link com.memgres.engine.ExprEvaluator#evalColumnRef}) or
 * {@code Table.isFunctionResult()}. This fires 42703 for {@code gs.date} before the fallback
 * (which resolves it as {@code date(gs)}) ever runs, as soon as the FROM-function query gains a
 * JOIN — exactly {@code ResultsDailyDao.listByInstallationIdAndDate}'s shape:
 * {@code FROM generate_series(...) AS gs(key) LEFT JOIN results_daily rd ON rd.d = gs.date}.
 */
class JoinAttributeNotationValidationTest {

    private static Memgres memgres;
    private static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(memgres.getJdbcUrl(), memgres.getUser(), memgres.getPassword());
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE rd2 (d date, v int)");
            s.execute("INSERT INTO rd2 VALUES ('2026-01-02', 42)");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (memgres != null) memgres.close();
    }

    @Test
    void gsDateAttributeNotation_resolvesInSelectListWithJoin() throws SQLException {
        // Minimal repro from mtask-7-report.md Group 1 (R7, CONFIRMED).
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT ON (gs.date) gs.date AS key, rd2.v "
                        + "FROM generate_series(?, ?, '1 day'::interval) AS gs(key) "
                        + "LEFT JOIN rd2 ON rd2.d = gs.date "
                        + "ORDER BY gs.date")) {
            ps.setObject(1, java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"));
            ps.setObject(2, java.time.OffsetDateTime.parse("2026-01-03T00:00:00Z"));
            try (ResultSet rs = ps.executeQuery()) {
                List<Integer> vals = new ArrayList<>();
                int count = 0;
                while (rs.next()) {
                    count++;
                    vals.add(rs.getObject(2) == null ? null : rs.getInt(2));
                }
                assertEquals(3, count);
                List<Integer> expected = new ArrayList<>();
                expected.add(null);
                expected.add(42);
                expected.add(null);
                assertEquals(expected, vals);
            }
        }
    }

    @Test
    void gsDateAttributeNotation_appExactShape() throws SQLException {
        // ResultsDailyDao.listByInstallationIdAndDate's actual query shape.
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT DISTINCT ON (gs.date) gs.date as key, rd2.v as v "
                             + "FROM generate_series('2026-01-01'::timestamptz, '2026-01-04'::timestamptz, '1 day'::interval) AS gs(key) "
                             + "LEFT JOIN rd2 ON rd2.d = gs.date "
                             + "ORDER BY gs.date")) {
            int count = 0;
            while (rs.next()) count++;
            assertEquals(4, count);
        }
    }

    // A genuinely unknown column reference in a joined query must still raise 42703 —
    // the fix must not silently swallow real errors.
    @Test
    void unknownColumnInJoinedQuery_stillRaises42703() {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (Statement s = conn.createStatement()) {
                s.executeQuery(
                        "SELECT gs.nonexistent_thing "
                                + "FROM generate_series('2026-01-01'::timestamptz, '2026-01-02'::timestamptz, '1 day'::interval) AS gs(key) "
                                + "LEFT JOIN rd2 ON rd2.d = gs.key::date");
            }
        });
        assertEquals("42703", ex.getSQLState());
        assertTrue(ex.getMessage().contains("nonexistent_thing"), "unexpected message: " + ex.getMessage());
    }

    // A genuinely unknown column on a regular (non-function) table alias in a joined query
    // must still raise 42703, matching prior behavior.
    @Test
    void unknownColumnOnRegularTableInJoin_stillRaises42703() {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (Statement s = conn.createStatement()) {
                s.executeQuery(
                        "SELECT rd2.nonexistent_column "
                                + "FROM rd2 LEFT JOIN rd2 AS rd3 ON rd3.d = rd2.d");
            }
        });
        assertEquals("42703", ex.getSQLState());
    }
}
