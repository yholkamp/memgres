package com.memgres.ddl;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug 5 (mtask-6): expression conflict targets rejected by the parser.
 *
 * Real-world repro (JobDao.insertPriceCheckJob / V154__price_check_pending_index_excludes_active.sql):
 *   CREATE UNIQUE INDEX jobs_price_check_one_pending
 *       ON jobs (queue_name, ((input->>'price_id')))
 *       WHERE queue_name = 'price_check' AND state < 'active';
 *
 *   INSERT INTO jobs (...) VALUES (...)
 *   ON CONFLICT (queue_name, ((input->>'price_id')))
 *     WHERE queue_name = 'price_check' AND state < 'active'
 *   DO NOTHING
 *
 * Before the fix both the mixed column/expression list in CREATE TABLE ... UNIQUE (...) and in
 * ON CONFLICT (...) threw 42601 "Expected identifier" because the column-list parsers only
 * accepted bare identifiers, never a parenthesized expression mixed in with plain columns.
 */
class ExpressionConflictTargetTest {

    static Memgres memgres;
    static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
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

    static void exec(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) { s.execute(sql); }
    }

    static int execUpdate(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) { return s.executeUpdate(sql); }
    }

    static String scalar(String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** CREATE TABLE ... UNIQUE (col, (expr)) must parse (bug 5, DdlTableParser). */
    @Test
    void create_table_mixed_unique_column_and_expression_parses() throws SQLException {
        exec("CREATE TABLE ect_ddl_t(id int, data jsonb, UNIQUE (id, (data->>'k')))");
        try {
            String count = scalar("SELECT count(*) FROM pg_class WHERE relname = 'ect_ddl_t'");
            assertEquals("1", count, "table with mixed UNIQUE column/expression should be created");
        } finally {
            exec("DROP TABLE IF EXISTS ect_ddl_t");
        }
    }

    /** The expression half of a mixed UNIQUE constraint must actually be enforced. */
    @Test
    void mixed_unique_expression_constraint_enforces_uniqueness() throws SQLException {
        exec("CREATE TABLE ect_enf_t(id int, data jsonb, UNIQUE (id, (data->>'k')))");
        try {
            exec("INSERT INTO ect_enf_t(id, data) VALUES (1, '{\"k\":\"a\"}')");
            // Same id AND same expression value -> violates the mixed unique constraint
            SQLException ex = assertThrows(SQLException.class,
                    () -> exec("INSERT INTO ect_enf_t(id, data) VALUES (1, '{\"k\":\"a\"}')"));
            assertEquals("23505", ex.getSQLState(),
                    "duplicate (id, data->>'k') should violate the mixed unique constraint, got "
                            + ex.getSQLState());
            // Same id, different expression value -> must be allowed
            exec("INSERT INTO ect_enf_t(id, data) VALUES (1, '{\"k\":\"b\"}')");
            String count = scalar("SELECT count(*) FROM ect_enf_t");
            assertEquals("2", count,
                    "differing expression value for the same id should not violate uniqueness");
        } finally {
            exec("DROP TABLE IF EXISTS ect_enf_t");
        }
    }

    /**
     * ON CONFLICT with a mixed column/expression target list (bug 5, DmlParser.parseOnConflict),
     * matching a CREATE UNIQUE INDEX with the identical mixed target and partial predicate —
     * this mirrors JobDao.insertPriceCheckJob exactly.
     */
    @Test
    void on_conflict_mixed_expression_target_do_nothing_matches_partial_unique_index() throws SQLException {
        // state is a real enum (mirrors jobs.state/job_state in V45__job_queue.sql) so that
        // "state < 'active'" compares by declared enum ordinal ('created' < 'active'), not
        // lexical string order (where "created" > "active").
        exec("CREATE TYPE ect_job_state AS ENUM ('created', 'retry', 'active', 'completed')");
        exec("CREATE TABLE ect_jobs_t(id serial PRIMARY KEY, queue_name text, input jsonb, state ect_job_state)");
        exec("CREATE UNIQUE INDEX ect_jobs_one_pending " +
             "ON ect_jobs_t (queue_name, ((input->>'price_id'))) " +
             "WHERE queue_name = 'price_check' AND state < 'active'");
        try {
            exec("INSERT INTO ect_jobs_t(queue_name, input, state) " +
                 "VALUES ('price_check', '{\"price_id\":\"p1\"}', 'created')");

            // Duplicate (queue_name, price_id) still pending ('created' < 'active') -> DO NOTHING must skip it
            int rows = execUpdate(
                "INSERT INTO ect_jobs_t(queue_name, input, state) " +
                "VALUES ('price_check', '{\"price_id\":\"p1\"}', 'created') " +
                "ON CONFLICT (queue_name, ((input->>'price_id'))) " +
                "  WHERE queue_name = 'price_check' AND state < 'active' " +
                "DO NOTHING");
            assertEquals(0, rows, "conflicting pending price_check job should be skipped by DO NOTHING");

            String count = scalar("SELECT count(*) FROM ect_jobs_t");
            assertEquals("1", count, "no duplicate row should have been inserted");

            // A different price_id must insert normally (no conflict)
            int rows2 = execUpdate(
                "INSERT INTO ect_jobs_t(queue_name, input, state) " +
                "VALUES ('price_check', '{\"price_id\":\"p2\"}', 'created') " +
                "ON CONFLICT (queue_name, ((input->>'price_id'))) " +
                "  WHERE queue_name = 'price_check' AND state < 'active' " +
                "DO NOTHING");
            assertEquals(1, rows2, "a distinct price_id should insert normally");
        } finally {
            exec("DROP TABLE IF EXISTS ect_jobs_t");
            exec("DROP TYPE IF EXISTS ect_job_state");
        }
    }
}
