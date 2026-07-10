package com.memgres.pgwire;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1 (mtask-8, from M6 review): the Bug 7 fix (see {@link EnumColumnOidResolutionTest}) keys off
 * {@code Column.getEnumTypeName()} to advertise the real per-type OID for enum columns, but that
 * name is only ever populated for a plain column reference. A projection-built expression that
 * infers {@code DataType.ENUM} (COALESCE over an enum column, a CASE branching to one, an enum
 * through a subquery/CTE rebuild, ...) had no Column to carry the name from, so
 * {@code PgWireValueFormatter.columnTypeOid} fell back to the ENUM placeholder OID 0 -- the exact
 * {@code castNonNull} crash Bug 7 fixed for plain columns, reopened for expressions.
 */
class ExpressionEnumOidResolutionTest {

    static Memgres memgres;
    static Connection conn;

    @BeforeAll
    static void setUp() throws Exception {
        memgres = Memgres.builder().port(0).build().start();
        conn = DriverManager.getConnection(memgres.getJdbcUrl(), memgres.getUser(), memgres.getPassword());
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

    /** The exact repro named in the task: COALESCE(mode, 'manual') via pgjdbc getObject. */
    @Test
    void coalesceOverEnumColumn_getObject_doesNotThrow() throws SQLException {
        exec("CREATE TYPE eeor_mode AS ENUM ('manual', 'auto')");
        exec("CREATE TABLE eeor_installations (id int PRIMARY KEY, mode eeor_mode)");
        exec("INSERT INTO eeor_installations VALUES (1, NULL)");
        exec("INSERT INTO eeor_installations VALUES (2, 'auto')");
        try {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT COALESCE(mode, 'manual') FROM eeor_installations ORDER BY id")) {
                assertTrue(rs.next());
                Object v1 = assertDoesNotThrow(() -> rs.getObject(1),
                        "rs.getObject on COALESCE(enum_col, literal) must not throw");
                assertEquals("manual", v1.toString());
                assertTrue(rs.next());
                Object v2 = assertDoesNotThrow(() -> rs.getObject(1));
                assertEquals("auto", v2.toString());
            }
        } finally {
            exec("DROP TABLE IF EXISTS eeor_installations");
            exec("DROP TYPE IF EXISTS eeor_mode");
        }
    }

    @Test
    void caseReturningEnum_getObject_doesNotThrow() throws SQLException {
        exec("CREATE TYPE eeor_status AS ENUM ('pending', 'done', 'unknown')");
        exec("CREATE TABLE eeor_tasks (id int PRIMARY KEY, done boolean)");
        exec("INSERT INTO eeor_tasks VALUES (1, true)");
        try {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT CASE WHEN done THEN 'done'::eeor_status ELSE 'pending'::eeor_status END FROM eeor_tasks")) {
                assertTrue(rs.next());
                Object v = assertDoesNotThrow(() -> rs.getObject(1));
                assertEquals("done", v.toString());
                ResultSetMetaData md = rs.getMetaData();
                assertEquals("eeor_status", assertDoesNotThrow(() -> md.getColumnTypeName(1)));
            }
        } finally {
            exec("DROP TABLE IF EXISTS eeor_tasks");
            exec("DROP TYPE IF EXISTS eeor_status");
        }
    }

    @Test
    void enumThroughCte_getObject_doesNotThrow() throws SQLException {
        exec("CREATE TYPE eeor_grade AS ENUM ('a', 'b', 'c')");
        exec("CREATE TABLE eeor_students (id int PRIMARY KEY, grade eeor_grade)");
        exec("INSERT INTO eeor_students VALUES (1, 'b')");
        try {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                         "WITH g AS (SELECT grade FROM eeor_students) SELECT grade FROM g")) {
                assertTrue(rs.next());
                Object v = assertDoesNotThrow(() -> rs.getObject(1));
                assertEquals("b", v.toString());
                ResultSetMetaData md = rs.getMetaData();
                assertEquals("eeor_grade", assertDoesNotThrow(() -> md.getColumnTypeName(1)));
            }
        } finally {
            exec("DROP TABLE IF EXISTS eeor_students");
            exec("DROP TYPE IF EXISTS eeor_grade");
        }
    }

    // Regression guard: a genuinely-unknown-enum-identity expression (e.g. a user-defined
    // function with an untyped return) must advertise safe TEXT, never a crashing unnamed ENUM.
    @Test
    void unknownEnumIdentityExpression_advertisesTextNotCrashingEnum() throws SQLException {
        exec("CREATE TYPE eeor_kind AS ENUM ('x', 'y')");
        exec("CREATE TABLE eeor_things (id int PRIMARY KEY, kind eeor_kind)");
        exec("INSERT INTO eeor_things VALUES (1, 'x')");
        try {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT kind::text || '!' FROM eeor_things")) {
                assertTrue(rs.next());
                assertEquals("text", rs.getMetaData().getColumnTypeName(1));
                assertEquals("x!", rs.getString(1));
            }
        } finally {
            exec("DROP TABLE IF EXISTS eeor_things");
            exec("DROP TYPE IF EXISTS eeor_kind");
        }
    }
}
