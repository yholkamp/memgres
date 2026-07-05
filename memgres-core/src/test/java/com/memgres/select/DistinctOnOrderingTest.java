package com.memgres.select;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug 10 (mtask-6): DISTINCT ON must return the FIRST row of each group under the full
 * ORDER BY, including ordering keys beyond the DISTINCT ON expressions.
 *
 * App pattern (ResultsMonthlyDao / ResultsDailyDao): {@code SELECT DISTINCT ON (...) ...
 * ORDER BY ..., CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END}
 * — the row with the lowest CASE rank must win per group.
 *
 * Root cause found while reproducing: DISTINCT ON's dedup itself was fine (it runs after the
 * full ORDER BY sort), but the ranking expression was a *simple CASE over an enum column*
 * ({@code type} is the custom enum {@code result_type}), and simple-CASE matching used raw
 * {@code Objects.equals} — a stored {@code AstExecutor.PgEnum} never equals the {@code String}
 * WHEN label, so every WHEN missed, every row ranked ELSE(3), the ORDER BY key was constant,
 * and an arbitrary (insertion-order) row won the group. Same silent wrongness applied to
 * {@code ORDER BY CASE enum ... LIMIT 1} scalar subqueries
 * (ResultsMonthlyDao.findKwhFallback, the ApiControllerTest kwh-fallback failure).
 * Fixed by making simple CASE use the {@code =} operator's equality (TypeCoercion.areEqual),
 * per PG's definition of simple CASE as {@code operand = whenValue}.
 */
class DistinctOnOrderingTest {

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

    static List<String> col(String sql, String colName) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(colName));
        }
        return out;
    }

    /** Third ORDER BY key is a CASE expression NOT in the select list (the app's exact shape). */
    @Test
    void distinct_on_case_ranking_key_picks_lowest_rank() throws SQLException {
        exec("CREATE TABLE don_case (installation_id int, month int, type text, kwh int)");
        // Same group (1, 1): 'api' (rank 2) inserted BEFORE 'manual' (rank 1) so insertion
        // order disagrees with the ORDER BY ranking; 'manual' must still win.
        exec("INSERT INTO don_case VALUES (1, 1, 'api', 999), (1, 1, 'manual', 111)");
        try {
            List<String> kwh = col(
                "SELECT DISTINCT ON (installation_id, month) kwh::text AS kwh " +
                "FROM don_case " +
                "ORDER BY installation_id, month, " +
                "  CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END",
                "kwh");
            assertEquals(Collections.singletonList("111"), kwh,
                    "manual (rank 1) should win over api (rank 2) in the group");
        } finally {
            exec("DROP TABLE IF EXISTS don_case");
        }
    }

    /** Third ORDER BY key is a plain column not in the select list. */
    @Test
    void distinct_on_plain_column_third_key_picks_first() throws SQLException {
        exec("CREATE TABLE don_plain (grp int, sub int, rank int, val text)");
        exec("INSERT INTO don_plain VALUES (1, 1, 5, 'loser'), (1, 1, 2, 'winner'), (2, 1, 9, 'only')");
        try {
            List<String> vals = col(
                "SELECT DISTINCT ON (grp, sub) val FROM don_plain ORDER BY grp, sub, rank",
                "val");
            assertEquals(Arrays.asList("winner", "only"), vals);
        } finally {
            exec("DROP TABLE IF EXISTS don_plain");
        }
    }

    /** Third ORDER BY key DESC — highest value must win. */
    @Test
    void distinct_on_desc_third_key_picks_highest() throws SQLException {
        exec("CREATE TABLE don_desc (grp int, ts int, val text)");
        exec("INSERT INTO don_desc VALUES (1, 10, 'old'), (1, 20, 'new')");
        try {
            List<String> vals = col(
                "SELECT DISTINCT ON (grp) val FROM don_desc ORDER BY grp, ts DESC",
                "val");
            assertEquals(Collections.singletonList("new"), vals);
        } finally {
            exec("DROP TABLE IF EXISTS don_desc");
        }
    }

    /** Expression third key (arithmetic) not in the select list. */
    @Test
    void distinct_on_expression_third_key() throws SQLException {
        exec("CREATE TABLE don_expr (grp int, a int, b int, val text)");
        exec("INSERT INTO don_expr VALUES (1, 5, 5, 'ten'), (1, 1, 2, 'three')");
        try {
            List<String> vals = col(
                "SELECT DISTINCT ON (grp) val FROM don_expr ORDER BY grp, a + b",
                "val");
            assertEquals(Collections.singletonList("three"), vals);
        } finally {
            exec("DROP TABLE IF EXISTS don_expr");
        }
    }

    /**
     * The actual failing shape: the CASE ranks an ENUM column against string labels.
     * Simple CASE must treat WHEN labels with = semantics (PgEnum matches its label), or the
     * rank collapses to a constant and DISTINCT ON silently returns an arbitrary row.
     */
    @Test
    void distinct_on_case_over_enum_column_picks_lowest_rank() throws SQLException {
        exec("CREATE TYPE don_result_type AS ENUM ('direct', 'manual', 'api', 'auto')");
        exec("CREATE TABLE don_enum (installation_id int, date date, type don_result_type, battery_charged int)");
        // 'auto' (rank 3) inserted BEFORE 'manual' (rank 1): insertion order must not win.
        exec("INSERT INTO don_enum VALUES (1, '2024-01-01', 'auto', 999), (1, '2024-01-01', 'manual', 111)");
        try {
            List<String> charged = col(
                "SELECT DISTINCT ON (installation_id) battery_charged::text AS battery_charged " +
                "FROM don_enum " +
                "ORDER BY installation_id, " +
                "  CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END",
                "battery_charged");
            assertEquals(Collections.singletonList("111"), charged,
                    "manual (rank 1) must beat auto (rank 3) when CASE ranks an enum column");
        } finally {
            exec("DROP TABLE IF EXISTS don_enum");
            exec("DROP TYPE IF EXISTS don_result_type");
        }
    }

    /**
     * The ResultsMonthlyDao.findKwhFallback shape: ORDER BY CASE-over-enum ... LIMIT 1 inside a
     * scalar subquery (the exact query behind ApiControllerTest.submitMonthlyResult_missingKwh_
     * prefersManualOverAuto's silent wrong result).
     */
    @Test
    void order_by_case_over_enum_limit_1_in_scalar_subquery_picks_lowest_rank() throws SQLException {
        exec("CREATE TYPE don_rt2 AS ENUM ('direct', 'manual', 'api', 'auto')");
        exec("CREATE TABLE don_rm (installation_id int, date date, type don_rt2, battery_charged int)");
        exec("INSERT INTO don_rm VALUES (1, '2024-01-01', 'auto', 999), (1, '2024-01-01', 'manual', 111)");
        try {
            List<String> charged = col(
                "SELECT (SELECT battery_charged FROM don_rm " +
                "         WHERE installation_id = 1 AND date = '2024-01-01' " +
                "           AND type <> 'api' AND battery_charged IS NOT NULL " +
                "         ORDER BY CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END " +
                "         LIMIT 1) AS battery_charged",
                "battery_charged");
            assertEquals(Collections.singletonList("111"), charged,
                    "kwh fallback must prefer manual (rank 1) over auto (rank 3)");
        } finally {
            exec("DROP TABLE IF EXISTS don_rm");
            exec("DROP TYPE IF EXISTS don_rt2");
        }
    }

    /** DISTINCT ON over a JOIN with qualified names (rm.x style, closer to the DAO SQL). */
    @Test
    void distinct_on_over_join_with_case_rank() throws SQLException {
        exec("CREATE TABLE don_m (month int)");
        exec("CREATE TABLE don_rm (installation_id int, month int, type text, kwh int)");
        exec("INSERT INTO don_m VALUES (1)");
        exec("INSERT INTO don_rm VALUES (7, 1, 'api', 999), (7, 1, 'manual', 111)");
        try {
            List<String> kwh = col(
                "SELECT DISTINCT ON (rm.installation_id, m.month) rm.kwh::text AS kwh " +
                "FROM don_m m JOIN don_rm rm ON rm.month = m.month " +
                "ORDER BY rm.installation_id, m.month, " +
                "  CASE rm.type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END",
                "kwh");
            assertEquals(Collections.singletonList("111"), kwh,
                    "manual (rank 1) should win over api (rank 2) across the join");
        } finally {
            exec("DROP TABLE IF EXISTS don_rm");
            exec("DROP TABLE IF EXISTS don_m");
        }
    }
}
