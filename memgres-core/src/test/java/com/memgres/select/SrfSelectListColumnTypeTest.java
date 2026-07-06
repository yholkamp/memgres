package com.memgres.select;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group 2 (mtask-8): a set-returning function (SRF) used directly as a SELECT-list target (e.g.
 * {@code SELECT generate_series(...) AS month}) advertised its RowDescription column type as
 * {@code text} regardless of the SRF's actual element type, because
 * {@code ExprEvaluator.inferTypeFromContext} had no case for {@code generate_series} and fell
 * through to the default {@code DataType.TEXT}. pgjdbc's {@code ResultSet.getObject}/
 * {@code getLocalDate}/{@code getTimestamp} then reject the mismatch
 * ("Cannot convert the column of type TEXT to requested type java.time.LocalDate"). This is the
 * exact shape behind {@code months AS (SELECT generate_series(date_trunc('month', ...), ...,
 * interval '1 month') AS month)} in the monthly-results CTE (ResultsMonthlyDao), which then
 * propagates the wrong type through the CTE wrapper.
 */
class SrfSelectListColumnTypeTest {

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

    @Test
    void bareSelectListGenerateSeriesOverTimestamptz_advertisesTimestamptz() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT generate_series(timestamptz '2024-01-01', timestamptz '2024-03-01', interval '1 month') AS month")) {
            assertEquals("timestamptz", rs.getMetaData().getColumnTypeName(1));
            int count = 0;
            while (rs.next()) {
                count++;
                assertNotNull(rs.getTimestamp(1));
            }
            assertEquals(3, count);
        }
    }

    @Test
    void cteAroundSelectListGenerateSeries_columnUsableAsLocalDate() throws SQLException {
        // Mirrors ResultsMonthlyDao's exact months CTE shape, including the "AT TIME ZONE" that
        // converts the timestamptz start bound to a plain timestamp (PG semantics: timestamptz
        // AT TIME ZONE z -> timestamp) -- generate_series then produces a timestamp series, which
        // is exactly what makes getObject(col, LocalDate.class) valid downstream (what jdbi's
        // ResultSetException wraps for its LocalDate column mapper).
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "WITH months AS ("
                             + "  SELECT generate_series("
                             + "    date_trunc('month', timestamptz '2024-01-15' AT TIME ZONE 'Europe/Amsterdam'),"
                             + "    date_trunc('month', timestamptz '2024-03-15' AT TIME ZONE 'Europe/Amsterdam'),"
                             + "    interval '1 month') AS month"
                             + ") SELECT m.month AS date FROM months m ORDER BY m.month")) {
            assertEquals("timestamp", rs.getMetaData().getColumnTypeName(1));
            java.util.List<LocalDate> dates = new java.util.ArrayList<>();
            while (rs.next()) {
                // getObject(col, LocalDate.class) is what actually surfaces the reported bug
                // (jdbi's ResultSetException wraps this exact conversion); getDate() is lenient
                // about the source column's advertised type and doesn't reproduce it.
                dates.add(rs.getObject(1, LocalDate.class));
            }
            assertEquals(java.util.List.of(
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1)), dates);
        }
    }

    @Test
    void bareSelectListGenerateSeriesOverIntegers_stillAdvertisesInteger() throws SQLException {
        // Regression guard: integer generate_series must not regress to text either.
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT generate_series(1, 3) AS n")) {
            assertEquals("int4", rs.getMetaData().getColumnTypeName(1));
        }
    }
}
