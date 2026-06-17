package com.memgres;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for two limitations reported against memgres 0.2.3
 * (issues for https://github.com/lhgravendeel/memgres), both now fixed:
 * <ul>
 *   <li><b>generate_series over temporal types</b> — date/timestamp/timestamptz inputs
 *       (including {@code now()}, typed literals and {@code TIMESTAMP WITH TIME ZONE})
 *       produce a proper series in both the SELECT list and the FROM clause; ascending
 *       and descending intervals work and a zero interval does not hang.</li>
 *   <li><b>range-partition literal bounds</b> — typed literals ({@code DATE '…'}),
 *       casts ({@code '…'::date}) and signed numbers ({@code -100}) parse as bounds.</li>
 * </ul>
 *
 * <p>The suites also cover the surrounding cases called out in review: integer / list /
 * hash partitioning, MINVALUE/MAXVALUE bounds, data routing, and the error paths
 * (ambiguous generate_series, overlapping bounds, out-of-range inserts).
 */
class GenerateSeriesAndPartitioningTest {

    static Memgres memgres;
    static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        // Simple query mode mirrors the rest of the suite and runs the raw SQL verbatim.
        conn = DriverManager.getConnection(
                memgres.getJdbcUrl() + "?preferQueryMode=simple",
                memgres.getUser(), memgres.getPassword());
        conn.setAutoCommit(true);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (memgres != null) memgres.close();
    }

    private static void exec(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) { s.execute(sql); }
    }

    private static int rowCount(String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = 0;
            while (rs.next()) n++;
            return n;
        }
    }

    private static void assertErrorContains(String sql, String needle) {
        SQLException ex = assertThrows(SQLException.class, () -> exec(sql), "expected an error for: " + sql);
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(needle),
                "expected error containing \"" + needle + "\" but got: " + ex.getMessage());
    }

    /** Whether a child table is attached as a partition (via pg_inherits). */
    private static boolean isPartitionAttached(String childName) throws SQLException {
        return rowCount("SELECT 1 FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
                + "WHERE c.relname = '" + childName + "'") == 1;
    }

    // =========================================================================
    // generate_series over temporal types
    // =========================================================================

    @Test
    void generateSeries_integerBaseline() throws SQLException {
        assertEquals(3, rowCount("SELECT generate_series(1, 3)"));
        assertEquals(3, rowCount("SELECT * FROM generate_series(1, 3)"));
    }

    @Test
    void generateSeries_dateLiteral() throws SQLException {
        assertEquals(5, rowCount(
                "SELECT generate_series(DATE '2026-01-01', DATE '2026-01-05', INTERVAL '1 day')"));
    }

    @Test
    void generateSeries_timestampLiteral() throws SQLException {
        // 00:00 .. 03:00 inclusive, stepping one hour -> 4 rows.
        assertEquals(4, rowCount(
                "SELECT generate_series(TIMESTAMP '2026-01-01 00:00:00', "
                        + "TIMESTAMP '2026-01-01 03:00:00', INTERVAL '1 hour')"));
    }

    @Test
    void generateSeries_timestampWithTimeZoneKeyword() throws SQLException {
        // day1 00:00 .. day2 00:00 inclusive, stepping six hours -> 00,06,12,18,00 = 5 rows.
        assertEquals(5, rowCount(
                "SELECT generate_series(TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00', "
                        + "TIMESTAMP WITH TIME ZONE '2026-01-02 00:00:00', INTERVAL '6 hours')"));
    }

    @Test
    void generateSeries_timestamptzNow_selectAndFrom() throws SQLException {
        // The originally reported repro — now() based, so assert a non-empty series.
        assertTrue(rowCount(
                        "SELECT generate_series(now() - interval '3 hours', now(), interval '1 hour')") >= 2,
                "now()-based timestamptz series (select list) should return multiple rows");
        assertTrue(rowCount(
                        "SELECT gs FROM generate_series(now() - interval '2 hours', now(), interval '1 hour') AS gs") >= 2,
                "now()-based timestamptz series (from clause) should return multiple rows");
    }

    @Test
    void generateSeries_timestamptzExplicitCast_returnsTimestamps() throws SQLException {
        // 00:00 .. 03:00 inclusive, stepping one hour -> 4 rows, in both positions.
        assertEquals(4, rowCount(
                "SELECT generate_series('2026-06-01 00:00:00+00'::timestamptz, "
                        + "'2026-06-01 03:00:00+00'::timestamptz, interval '1 hour')"));
        assertEquals(4, rowCount(
                "SELECT * FROM generate_series('2026-06-01 00:00:00+00'::timestamptz, "
                        + "'2026-06-01 03:00:00+00'::timestamptz, interval '1 hour')"));

        // The produced values must be real timestamps, not integers.
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT generate_series('2026-06-01 00:00:00+00'::timestamptz, "
                             + "'2026-06-01 02:00:00+00'::timestamptz, interval '1 hour') AS ts ORDER BY ts")) {
            assertTrue(rs.next());
            assertNotNull(rs.getTimestamp(1), "generate_series should yield timestamp values");
        }
    }

    @Test
    void generateSeries_descendingNegativeInterval() throws SQLException {
        // Dates counting down: 01-05, 01-04, 01-03, 01-02, 01-01 -> 5 rows.
        assertEquals(5, rowCount(
                "SELECT generate_series(DATE '2026-01-05', DATE '2026-01-01', INTERVAL '-1 day')"));
        // Same for timestamptz.
        assertTrue(rowCount(
                        "SELECT generate_series(now(), now() - interval '2 hours', interval '-1 hour')") >= 2,
                "descending timestamptz series should return multiple rows");
    }

    @Test
    void generateSeries_fromClauseTimestamp() throws SQLException {
        assertEquals(3, rowCount(
                "SELECT gs FROM generate_series(TIMESTAMP '2026-01-01', TIMESTAMP '2026-01-03', "
                        + "INTERVAL '1 day') AS gs"));
    }

    @Test
    void generateSeries_ambiguousWithoutStep_errors() {
        // Bare date strings with no interval step are ambiguous and must error (not hang,
        // not silently coerce to integers).
        assertErrorContains("SELECT generate_series('2026-01-01', '2026-01-05')", "is not unique");
    }

    @Test
    void generateSeries_zeroInterval_doesNotHang() throws SQLException {
        // Zero step must terminate (empty result), never loop forever.
        assertEquals(0, rowCount("SELECT generate_series(1, 10, 0)"));
        assertEquals(0, rowCount(
                "SELECT generate_series(TIMESTAMP '2026-01-01', TIMESTAMP '2026-01-02', INTERVAL '0 seconds')"));
    }

    // =========================================================================
    // Range / list / hash partitioning with literal bounds
    // =========================================================================

    @Test
    void rangePartition_integerBounds_routingAndErrors() throws SQLException {
        exec("CREATE TABLE part_int (id int) PARTITION BY RANGE (id)");
        exec("CREATE TABLE part_int_1 PARTITION OF part_int FOR VALUES FROM (1) TO (100)");
        exec("CREATE TABLE part_int_2 PARTITION OF part_int FOR VALUES FROM (100) TO (200)");
        assertTrue(isPartitionAttached("part_int_1"));
        assertTrue(isPartitionAttached("part_int_2"));

        // Routing.
        exec("INSERT INTO part_int VALUES (50), (150)");
        assertEquals(1, rowCount("SELECT * FROM part_int_1"));
        assertEquals(1, rowCount("SELECT * FROM part_int_2"));

        // Overlapping bounds must be rejected.
        assertErrorContains(
                "CREATE TABLE part_int_x PARTITION OF part_int FOR VALUES FROM (50) TO (150)", "overlap");
        // A value outside every partition must be rejected.
        assertErrorContains("INSERT INTO part_int VALUES (999)", "no partition");
    }

    @Test
    void rangePartition_typedDateLiteralBound_routing() throws SQLException {
        exec("CREATE TABLE part_date (d date) PARTITION BY RANGE (d)");
        // The exact form that produced the report's parse error.
        exec("CREATE TABLE part_date_2026_04 PARTITION OF part_date "
                + "FOR VALUES FROM (DATE '2026-04-01') TO (DATE '2026-05-01')");
        assertTrue(isPartitionAttached("part_date_2026_04"));

        exec("INSERT INTO part_date VALUES (DATE '2026-04-15')");
        assertEquals(1, rowCount("SELECT * FROM part_date_2026_04"),
                "row should be routed into the April partition");
    }

    @Test
    void rangePartition_castBound() throws SQLException {
        exec("CREATE TABLE part_cast (d date) PARTITION BY RANGE (d)");
        exec("CREATE TABLE part_cast_q1 PARTITION OF part_cast "
                + "FOR VALUES FROM ('2026-01-01'::date) TO ('2026-04-01'::date)");
        assertTrue(isPartitionAttached("part_cast_q1"));
    }

    @Test
    void rangePartition_stringLiteralBound() throws SQLException {
        // Implicit cast — the original minimal repro, which already parsed.
        exec("CREATE TABLE part_str (d date) PARTITION BY RANGE (d)");
        exec("CREATE TABLE part_str_jan PARTITION OF part_str "
                + "FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')");
        assertTrue(isPartitionAttached("part_str_jan"));
    }

    @Test
    void rangePartition_negativeNumberBounds_routing() throws SQLException {
        exec("CREATE TABLE part_neg (val int) PARTITION BY RANGE (val)");
        exec("CREATE TABLE part_neg_low PARTITION OF part_neg FOR VALUES FROM (-1000) TO (0)");
        exec("CREATE TABLE part_neg_high PARTITION OF part_neg FOR VALUES FROM (0) TO (1000)");
        assertTrue(isPartitionAttached("part_neg_low"));
        assertTrue(isPartitionAttached("part_neg_high"));

        exec("INSERT INTO part_neg VALUES (-500), (500)");
        assertEquals(1, rowCount("SELECT * FROM part_neg_low"));
        assertEquals(1, rowCount("SELECT * FROM part_neg_high"));
    }

    @Test
    void rangePartition_minValueMaxValueBounds_routing() throws SQLException {
        exec("CREATE TABLE part_mm (id int) PARTITION BY RANGE (id)");
        exec("CREATE TABLE part_mm_low PARTITION OF part_mm FOR VALUES FROM (MINVALUE) TO (0)");
        exec("CREATE TABLE part_mm_high PARTITION OF part_mm FOR VALUES FROM (0) TO (MAXVALUE)");
        assertTrue(isPartitionAttached("part_mm_low"));
        assertTrue(isPartitionAttached("part_mm_high"));

        exec("INSERT INTO part_mm VALUES (-99999), (99999)");
        assertEquals(1, rowCount("SELECT * FROM part_mm_low"));
        assertEquals(1, rowCount("SELECT * FROM part_mm_high"));
    }

    @Test
    void rangePartition_timestampTypedBound_routing() throws SQLException {
        exec("CREATE TABLE part_ts (t timestamp) PARTITION BY RANGE (t)");
        exec("CREATE TABLE part_ts_q1 PARTITION OF part_ts "
                + "FOR VALUES FROM (TIMESTAMP '2026-01-01') TO (TIMESTAMP '2026-04-01')");
        assertTrue(isPartitionAttached("part_ts_q1"));

        exec("INSERT INTO part_ts VALUES (TIMESTAMP '2026-02-01 12:00:00')");
        assertEquals(1, rowCount("SELECT * FROM part_ts_q1"));
    }

    @Test
    void listPartition_routing() throws SQLException {
        exec("CREATE TABLE part_list (status text) PARTITION BY LIST (status)");
        exec("CREATE TABLE part_list_active PARTITION OF part_list FOR VALUES IN ('active', 'pending')");
        exec("CREATE TABLE part_list_done PARTITION OF part_list FOR VALUES IN ('done', 'archived')");
        assertTrue(isPartitionAttached("part_list_active"));
        assertTrue(isPartitionAttached("part_list_done"));

        exec("INSERT INTO part_list VALUES ('active'), ('pending'), ('done')");
        assertEquals(2, rowCount("SELECT * FROM part_list_active"));
        assertEquals(1, rowCount("SELECT * FROM part_list_done"));
    }

    @Test
    void hashPartition_routing() throws SQLException {
        exec("CREATE TABLE part_hash (id int) PARTITION BY HASH (id)");
        exec("CREATE TABLE part_hash_0 PARTITION OF part_hash FOR VALUES WITH (MODULUS 4, REMAINDER 0)");
        exec("CREATE TABLE part_hash_1 PARTITION OF part_hash FOR VALUES WITH (MODULUS 4, REMAINDER 1)");
        exec("CREATE TABLE part_hash_2 PARTITION OF part_hash FOR VALUES WITH (MODULUS 4, REMAINDER 2)");
        exec("CREATE TABLE part_hash_3 PARTITION OF part_hash FOR VALUES WITH (MODULUS 4, REMAINDER 3)");
        assertTrue(isPartitionAttached("part_hash_0"));

        // With all four buckets present, every row routes somewhere.
        exec("INSERT INTO part_hash SELECT generate_series(1, 20)");
        assertEquals(20, rowCount("SELECT * FROM part_hash"));
    }
}
