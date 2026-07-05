package com.memgres.protocol;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug 3 (Describe -> NoData fallback) regression tests.
 *
 * Two independent triggers, both must be fixed:
 * (i) an internal NPE during the LIMIT-0 describe-time execution of a row-returning
 *     statement is swallowed and turned into NoData, so pgjdbc believes the statement
 *     returns no rows (jdbi then throws NoResultsException with no server error at all).
 * (ii) isWithSelect(String) is a naive character scanner that does not skip string
 *      literals, so parens/DML keywords embedded in a literal desync it and misclassify
 *      a `WITH ... SELECT` as non-row-returning.
 *
 * Policy: a row-returning statement must never get NoData. If describe-time execution
 * genuinely fails (e.g. the query references a missing column), the failure must be
 * surfaced to the client as an ErrorResponse instead of being silently swallowed.
 */
class DescribeNoDataFallbackTest {

    static Memgres memgres;
    static Connection conn;
    // A separate connection with prepareThreshold=1 forces pgjdbc to promote the
    // PreparedStatement to a named server-side statement on its 2nd use, which makes
    // it issue a real Describe(Statement) message (params not yet bound) instead of
    // only ever Describe(Portal) (params already bound) — this is the path that
    // exercises PgWireDescribeHelper.describeStatement's NULL-parameter substitution.
    static Connection namedStmtConn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(memgres.getJdbcUrl(), memgres.getUser(), memgres.getPassword());
        conn.setAutoCommit(true);
        namedStmtConn = DriverManager.getConnection(
                memgres.getJdbcUrl() + "?prepareThreshold=1", memgres.getUser(), memgres.getPassword());
        namedStmtConn.setAutoCommit(true);
        exec("CREATE TABLE dndf_results (id serial PRIMARY KEY, installation_id int, ts timestamptz, val numeric)");
        exec("INSERT INTO dndf_results (installation_id, ts, val) VALUES (1, '2025-01-15 10:00:00+01', 42)");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (namedStmtConn != null) namedStmtConn.close();
        if (memgres != null) memgres.close();
    }

    static void exec(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) { s.execute(sql); }
    }

    /** (i) Months-CTE shape with a timestamptz parameter fed through generate_series/date_trunc/AT TIME ZONE. */
    @Test
    void monthsCteWithTimestamptzParamReturnsRows() throws Exception {
        String sql = """
            WITH months AS (
              SELECT generate_series(
                  date_trunc('month', ?::timestamptz at time zone 'Europe/Amsterdam'),
                  date_trunc('month', ?::timestamptz at time zone 'Europe/Amsterdam' - interval '1 day'),
                  interval '1 month'
              ) AS month
            )
            SELECT m.month AS date, r.installation_id, r.val
            FROM months m
            LEFT JOIN dndf_results r ON date_trunc('month', r.ts at time zone 'Europe/Amsterdam') = m.month
            ORDER BY m.month
            """;
        try (PreparedStatement ps = namedStmtConn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf("2025-01-01 00:00:00"));
            ps.setTimestamp(2, Timestamp.valueOf("2025-03-01 00:00:00"));
            // 1st execution: unnamed statement (prepareThreshold=1 means the *next* use gets named).
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { /* drain */ } }

            // 2nd execution: pgjdbc now promotes to a named server-side statement, issuing
            // Parse(name) + Describe(Statement, name) before any Bind — this is where
            // describeStatement's $N -> NULL substitution feeds the months CTE.
            ps.setTimestamp(1, Timestamp.valueOf("2025-01-01 00:00:00"));
            ps.setTimestamp(2, Timestamp.valueOf("2025-03-01 00:00:00"));
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                assertEquals(3, md.getColumnCount(), "expected RowDescription with 3 columns, not NoData");
                int rows = 0;
                while (rs.next()) rows++;
                assertTrue(rows > 0, "expected the months CTE to yield rows");
            }
        }
    }

    /** (ii) A single-quoted string literal containing a closing paren + DML keyword must not desync isWithSelect. */
    @Test
    void withSelectLiteralContainingParenAndDeleteIsNotMisclassified() throws Exception {
        String sql = "WITH x AS (SELECT ') DELETE' AS s) SELECT s FROM x";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                assertEquals(1, md.getColumnCount());
                assertTrue(rs.next());
                assertEquals(") DELETE", rs.getString(1));
            }
        }
    }

    /** A genuinely broken row-returning statement must surface an ErrorResponse, never silent NoData. */
    @Test
    void genuinelyBrokenSelectSurfacesErrorNotNoData() throws Exception {
        try (PreparedStatement ps = namedStmtConn.prepareStatement(
                "SELECT nonexistent_column FROM dndf_results WHERE id = ?")) {
            ps.setInt(1, 1);
            // 1st execution promotes the statement to named on the *next* use; the SQL is
            // broken regardless of bound values so this one also throws — that's fine,
            // we only care that an ErrorResponse (not silence/NoData) comes back.
            assertThrows(SQLException.class, ps::executeQuery);

            ps.setInt(1, 1);
            SQLException ex = assertThrows(SQLException.class, ps::executeQuery);
            assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("nonexistent_column"),
                    "expected the real column-not-found error, got: " + ex.getMessage());
        }
    }
}
