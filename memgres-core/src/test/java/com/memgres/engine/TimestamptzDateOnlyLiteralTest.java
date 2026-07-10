package com.memgres.engine;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression: date-only timestamptz literal with a timezone name, e.g. timestamptz '2024-01-01 UTC'. */
class TimestamptzDateOnlyLiteralTest {

    static Memgres memgres;
    static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(memgres.getJdbcUrl(), memgres.getUser(), memgres.getPassword());
        try (Statement st = conn.createStatement()) {
            st.execute("SET TIME ZONE 'UTC'");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (memgres != null) memgres.close();
    }

    @Test
    void dateOnlyTimestamptzLiteralInGenerateSeries() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT generate_series(timestamptz '2024-01-01 UTC', timestamptz '2024-03-01 UTC', interval '1 month') AS month")) {
            assertTrue(rs.next());
            assertEquals("2024-01-01 00:00:00+00", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("2024-02-01 00:00:00+00", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("2024-03-01 00:00:00+00", rs.getString(1));
            assertFalse(rs.next());
        }
    }
}
