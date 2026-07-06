package com.memgres.types;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group 3 (mtask-8): a stored timestamptz column compared against a text-format bind parameter
 * (Oid.UNSPECIFIED / text protocol — how jdbi binds {@code java.time.Instant} via
 * {@code setTimestamp}, and how any text-protocol connection sends parameters) falls through to
 * {@code TypeCoercion.pgStringCompare} because the parameter is stored as a plain Java
 * {@code String} at bind time (see {@code PgWireHandler.handleBind}, format==0 branch) and
 * {@code TypeCoercion.compare} had no String-vs-temporal branch. {@code OffsetDateTime.toString()}
 * uses {@code 'T'} as the date/time separator while PG's text format uses {@code ' '}; since
 * {@code 'T'} (0x54) &gt; {@code ' '} (0x20), every stored timestamptz compared greater than every
 * bound text parameter regardless of value — {@code <}/{@code <=}/{@code BETWEEN} were always
 * false, {@code >}/{@code >=} always true. Silent wrong results, not an error.
 *
 * The connection is forced to text-protocol params ({@code binaryTransfer=false}) to reproduce
 * the exact wire shape, mirroring the app's own workaround (see backend commit
 * "Force text-format JDBC transfer for memgres tests").
 */
class TimestamptzTextParamComparisonTest {

    private static Memgres memgres;
    private static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(
                memgres.getJdbcUrl() + "?binaryTransfer=false", memgres.getUser(), memgres.getPassword());
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE tstz_param_t (id int PRIMARY KEY, ts timestamptz)");
            s.execute("INSERT INTO tstz_param_t VALUES (1, '2026-01-13 00:00:00+00')");
            s.execute("INSERT INTO tstz_param_t VALUES (2, '2026-01-13 12:00:00+00')");
            s.execute("INSERT INTO tstz_param_t VALUES (3, '2026-01-14 00:00:00+00')");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (memgres != null) memgres.close();
    }

    @Test
    void betweenWithBoundInstantParams_findsRowsInRange() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM tstz_param_t WHERE ts BETWEEN ? AND ? ORDER BY id")) {
            ps.setTimestamp(1, Timestamp.from(Instant.parse("2026-01-13T00:00:00Z")));
            ps.setTimestamp(2, Timestamp.from(Instant.parse("2026-01-13T23:59:59Z")));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                while (rs.next()) ids.add(rs.getInt(1));
                assertEquals(java.util.List.of(1, 2), ids);
            }
        }
    }

    @Test
    void lessThanWithBoundInstantParam_excludesLaterRows() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM tstz_param_t WHERE ts < ? ORDER BY id")) {
            ps.setTimestamp(1, Timestamp.from(Instant.parse("2026-01-13T12:00:00Z")));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                while (rs.next()) ids.add(rs.getInt(1));
                assertEquals(java.util.List.of(1), ids);
            }
        }
    }

    @Test
    void greaterOrEqualWithBoundInstantParam_includesFromBoundary() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM tstz_param_t WHERE ts >= ? ORDER BY id")) {
            ps.setTimestamp(1, Timestamp.from(Instant.parse("2026-01-13T12:00:00Z")));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                while (rs.next()) ids.add(rs.getInt(1));
                assertEquals(java.util.List.of(2, 3), ids);
            }
        }
    }

    @Test
    void lessEqualWithIsoLiteralOmittingSeconds_includesExactBoundaryRow() throws SQLException {
        // '...T00:01Z' vs OffsetDateTime.toString() '...T00:01:00Z' — the seconds-omission
        // report footnote (Group 3 mode 2): boundary-equal row must still be included in <=.
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE tstz_seconds_t (ts timestamptz)");
            s.execute("INSERT INTO tstz_seconds_t VALUES ('2026-01-13 00:01:00+00')");
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM tstz_seconds_t WHERE ts <= '2026-01-13T00:01:00Z'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            s.execute("DROP TABLE tstz_seconds_t");
        }
    }

    @Test
    void unparseableTextOperand_raisesInvalidDatetimeError() {
        SQLException ex = assertThrows(SQLException.class, () -> {
            try (Statement s = conn.createStatement()) {
                s.executeQuery("SELECT id FROM tstz_param_t WHERE ts < 'not-a-timestamp'");
            }
        });
        assertTrue("22007".equals(ex.getSQLState()) || "22008".equals(ex.getSQLState()),
                "expected 22007/22008, got " + ex.getSQLState() + ": " + ex.getMessage());
    }

    @Test
    void equalityWithBoundInstantParam_stillWorks() throws SQLException {
        // Regression guard: report notes '=' bound lookup already worked pre-fix.
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM tstz_param_t WHERE ts = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.parse("2026-01-14T00:00:00Z")));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
                assertFalse(rs.next());
            }
        }
    }
}
