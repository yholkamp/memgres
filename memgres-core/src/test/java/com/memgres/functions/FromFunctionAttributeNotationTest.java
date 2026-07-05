package com.memgres.functions;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug 2 (attribute-notation fallback for FROM-function aliases): PostgreSQL resolves
 * {@code alias.name} as a column first, then falls back to attribute notation
 * ({@code alias.name} ≡ {@code name(alias)}) when {@code name} isn't a column. For a
 * single-column FROM-function alias like {@code generate_series(...) AS gs(key)}, this means
 * {@code gs.date} ≡ {@code date(gs)} (the timestamp→date cast), even though the declared column
 * is named {@code key}.
 *
 * Mirrors the real app failure: {@code ResultsDailyDao.listByInstallationIdAndDate} builds
 * {@code FROM generate_series(:start, :end, '1 day'::interval) AS gs(key)} and references
 * {@code gs.date} throughout the SELECT list / DISTINCT ON / ORDER BY.
 */
class FromFunctionAttributeNotationTest {

    private static Memgres memgres;
    private static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(memgres.getJdbcUrl(), memgres.getUser(), memgres.getPassword());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (memgres != null) memgres.close();
    }

    // ---- The app's exact failing shape ----

    @Test
    void gsDateAttributeNotation_resolvesLikeDateCast() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT DISTINCT ON (gs.date) gs.date AS key "
                             + "FROM generate_series('2026-01-01'::timestamptz, '2026-01-05'::timestamptz, '1 day'::interval) AS gs(key) "
                             + "ORDER BY gs.date")) {
            List<LocalDate> dates = new ArrayList<>();
            while (rs.next()) {
                dates.add(rs.getDate(1).toLocalDate());
            }
            assertEquals(
                    List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3),
                            LocalDate.of(2026, 1, 4), LocalDate.of(2026, 1, 5)),
                    dates);
        }
    }

    // ---- Column resolution must still win over the attribute-notation fallback ----

    @Test
    void realColumnWinsOverAttributeNotationFallback() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT gs.key, gs.date "
                             + "FROM generate_series('2026-01-01'::timestamptz, '2026-01-03'::timestamptz, '1 day'::interval) AS gs(key) "
                             + "ORDER BY gs.key")) {
            int count = 0;
            while (rs.next()) {
                count++;
                Timestamp keyVal = rs.getTimestamp(1);
                Date dateVal = rs.getDate(2);
                assertNotNull(keyVal, "gs.key must resolve to the real column (the full timestamp)");
                assertNotNull(dateVal, "gs.date must resolve via attribute notation to date(gs)");
                assertEquals(keyVal.toLocalDateTime().toLocalDate(), dateVal.toLocalDate());
            }
            assertEquals(3, count);
        }
    }

    @Test
    void columnNamedLikeAFunctionStillResolvesAsColumn() throws SQLException {
        // gs(date) makes "date" the real column name; gs.date must return the column's raw
        // value (an integer here), not attempt the date-cast fallback.
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT gs.date FROM generate_series(1, 3) AS gs(date) ORDER BY gs.date")) {
            List<Integer> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getInt(1));
            }
            assertEquals(List.of(1, 2, 3), values);
        }
    }

    // ---- WITH ORDINALITY on a timestamp series ----

    @Test
    void withOrdinality_onTimestampSeries_exposesBothAliasedColumns() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT ts, ord "
                             + "FROM generate_series('2026-01-01'::timestamptz, '2026-01-03'::timestamptz, '1 day'::interval) "
                             + "WITH ORDINALITY AS gs(ts, ord) "
                             + "ORDER BY ord")) {
            long expectedOrd = 1;
            LocalDate expectedDate = LocalDate.of(2026, 1, 1);
            int count = 0;
            while (rs.next()) {
                count++;
                assertNotNull(rs.getTimestamp(1));
                assertEquals(expectedOrd, rs.getLong(2));
                expectedOrd++;
            }
            assertEquals(3, count);
        }
    }

    // ---- The fallback must NOT fire for non-function FROM items ----
    // PostgreSQL's attribute notation on table/subquery aliases operates on the composite row
    // type (date(t) where t is a record), never by casting the single column's value — so
    // alias.castname on a one-column subquery/VALUES/table alias is a plain 42703 in PG.

    @Test
    void castNameOnSingleColumnSubqueryAlias_raises42703() {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (Statement s = conn.createStatement()) {
                s.executeQuery("SELECT t.date FROM (SELECT '2026-01-01 10:00:00'::timestamp AS ts) AS t");
            }
        });
        assertEquals("42703", ex.getSQLState());
        assertTrue(ex.getMessage().contains("t.date"), "unexpected message: " + ex.getMessage());
    }

    @Test
    void castNameOnSingleColumnValuesAlias_raises42703() {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (Statement s = conn.createStatement()) {
                s.executeQuery("SELECT v.text FROM (VALUES (1)) AS v(x)");
            }
        });
        assertEquals("42703", ex.getSQLState());
        assertTrue(ex.getMessage().contains("v.text"), "unexpected message: " + ex.getMessage());
    }

    @Test
    void enumTypeNameOnSingleColumnSubqueryAlias_raises42703() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TYPE attr_notation_mood AS ENUM ('happy', 'sad')");
        }
        try {
            SQLException ex = assertThrows(SQLException.class, () -> {
                try (Statement s = conn.createStatement()) {
                    s.executeQuery("SELECT t.attr_notation_mood FROM (SELECT 'happy' AS val) AS t");
                }
            });
            assertEquals("42703", ex.getSQLState());
        } finally {
            try (Statement s = conn.createStatement()) {
                s.execute("DROP TYPE attr_notation_mood");
            }
        }
    }

    @Test
    void castNameOnSingleColumnRealTableAlias_raises42703() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE attr_notation_one_col (ts timestamp)");
            s.execute("INSERT INTO attr_notation_one_col VALUES ('2026-01-01 10:00:00')");
        }
        try {
            SQLException ex = assertThrows(SQLException.class, () -> {
                try (Statement s = conn.createStatement()) {
                    s.executeQuery("SELECT t.date FROM attr_notation_one_col AS t");
                }
            });
            assertEquals("42703", ex.getSQLState());
        } finally {
            try (Statement s = conn.createStatement()) {
                s.execute("DROP TABLE attr_notation_one_col");
            }
        }
    }

    // ---- WITH ORDINALITY without a column-alias list (parser flag is the source of truth) ----

    @Test
    void withOrdinality_withoutColumnAliases_addsOrdinalityColumn() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM generate_series(10, 12) WITH ORDINALITY")) {
            assertEquals(2, rs.getMetaData().getColumnCount());
            assertEquals("ordinality", rs.getMetaData().getColumnName(2).toLowerCase());
            long expectedOrd = 1;
            int expectedVal = 10;
            while (rs.next()) {
                assertEquals(expectedVal++, rs.getInt(1));
                assertEquals(expectedOrd++, rs.getLong(2));
            }
            assertEquals(13, expectedVal);
        }
    }

    // ---- Unknown attribute must still raise 42703 ----

    @Test
    void unknownAttribute_stillRaises42703() {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (Statement s = conn.createStatement()) {
                s.executeQuery(
                        "SELECT gs.nonexistent_thing "
                                + "FROM generate_series('2026-01-01'::timestamptz, '2026-01-03'::timestamptz, '1 day'::interval) AS gs(key)");
            }
        });
        assertEquals("42703", ex.getSQLState());
        assertTrue(ex.getMessage().contains("gs.nonexistent_thing"), "unexpected message: " + ex.getMessage());
    }
}
