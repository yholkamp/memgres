package com.memgres.types;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Group 6 (mtask-8): investigation of the reported "ON CONFLICT expression arbiter silently
 * bypassed" bug found the true root cause is one level deeper than arbiter matching.
 * {@code DmlExecutor.executeInsert}'s "Validate enum values and wrap as PgEnum for correct
 * ordering" step (assigning the value its declared ordinal so {@code <}/{@code <=}/{@code >}/
 * {@code >=}/{@code ORDER BY}/simple {@code CASE} use PG's declaration order, not string order)
 * ran only over explicitly-provided ({@code filledCols}) columns. A column populated via its
 * column DEFAULT (e.g. {@code state job_state DEFAULT 'created'}, never listed in the INSERT's
 * column list) stayed a raw Java {@code String} forever ({@code TypeCoercion.coerce}/
 * {@code coerceForStorage} have no {@code ENUM} case), so any ordering comparison against it fell
 * back to lexicographic string comparison: {@code 'created' < 'active'} is PG-true (ordinal 0 < 2)
 * but was memgres-false ('c' &gt; 'a'). This is exactly what made
 * {@code JobDao.insertPriceCheckJob}'s partial-index predicate (
 * {@code WHERE queue_name = 'price_check' AND state < 'active'}) silently evaluate false for a
 * newly-DEFAULT-'created' row, so the ON-CONFLICT conflict search bailed out before ever reaching
 * the (already-correct) expression-arbiter matching, letting a duplicate row through DO NOTHING.
 */
class EnumDefaultValueOrderingTest {

    private static Memgres memgres;
    private static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(memgres.getJdbcUrl(), memgres.getUser(), memgres.getPassword());
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TYPE job_state_g6 AS ENUM ('created','retry','active','completed','cancelled','failed')");
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (conn != null) conn.close();
        if (memgres != null) memgres.close();
    }

    @Test
    void enumColumnPopulatedViaDefault_comparesByDeclarationOrder() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE g6_jobs (id int, state job_state_g6 not null default 'created')");
            s.execute("INSERT INTO g6_jobs (id) VALUES (1)"); // state via DEFAULT, not listed
            try (ResultSet rs = s.executeQuery("SELECT state < 'active', state > 'active', state = 'created' FROM g6_jobs")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1), "'created' must sort before 'active' by declared enum order");
                assertFalse(rs.getBoolean(2));
                assertTrue(rs.getBoolean(3));
            }
            s.execute("DROP TABLE g6_jobs");
        }
    }

    @Test
    void onConflictExpressionArbiter_detectsConflictOnDefaultPopulatedEnumRow() throws SQLException {
        // The exact real-world repro: JobDao.insertPriceCheckJob's shape (partial expression
        // unique index; INSERT never lists `state`, relying on its DEFAULT 'created').
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE g6_jobs2 (id serial primary key, queue_name text, "
                    + "state job_state_g6 not null default 'created', input json)");
            s.execute("CREATE UNIQUE INDEX g6_jobs2_one_pending ON g6_jobs2 (queue_name, ((input->>'price_id'))) "
                    + "WHERE queue_name = 'price_check' AND state < 'active'");
        }
        String insertSql = "INSERT INTO g6_jobs2 (queue_name, input) VALUES (?, cast(? AS json)) "
                + "ON CONFLICT (queue_name, ((input->>'price_id'))) "
                + "WHERE queue_name = 'price_check' AND state < 'active' DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, "price_check");
            ps.setString(2, "{\"price_id\": 42}");
            assertEquals(1, ps.executeUpdate(), "first insert should succeed (no existing conflicting row)");
        }
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, "price_check");
            ps.setString(2, "{\"price_id\": 42}");
            assertEquals(0, ps.executeUpdate(),
                    "duplicate insert must be skipped via ON CONFLICT DO NOTHING, not silently stored");
        }
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM g6_jobs2")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "the unique index must not be silently violated");
        }
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE g6_jobs2");
        }
    }

    // Regression guard: explicitly-provided enum values (not defaults) must keep working, and an
    // invalid explicit label must still be rejected.
    @Test
    void explicitEnumValue_stillValidatedAndOrdered() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE g6_jobs3 (id int, state job_state_g6)");
            s.execute("INSERT INTO g6_jobs3 VALUES (1, 'retry')");
            try (ResultSet rs = s.executeQuery("SELECT state < 'active' FROM g6_jobs3")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1));
            }
            SQLException ex = assertThrows(SQLException.class,
                    () -> s.execute("INSERT INTO g6_jobs3 VALUES (2, 'bogus')"));
            assertEquals("22P02", ex.getSQLState());
            s.execute("DROP TABLE g6_jobs3");
        }
    }
}
