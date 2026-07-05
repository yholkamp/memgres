package com.memgres.pgwire;

import com.memgres.core.Memgres;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug 7 (mtask-6): pgjdbc cannot resolve the OID memgres advertises in RowDescription for a
 * custom enum column.
 *
 * Root cause: {@code PgWireValueFormatter.sendRowDescription} advertised {@code DataType.ENUM}'s
 * hardcoded placeholder OID (0) for every enum column, regardless of which named enum type it
 * actually was. pgjdbc's {@code TypeInfoCache.getPGType(int)} treats OID 0 as
 * {@code Oid.UNSPECIFIED} and returns {@code null} without even querying {@code pg_type}; that
 * null then reaches {@code PgResultSet.initSqlType} via {@code castNonNull(...)}, throwing
 * {@code AssertionError: Misuse of castNonNull: called with a null argument}.
 *
 * This uses the default (extended-protocol) connection deliberately — the crash is specifically
 * about pgjdbc's client-side OID resolution, which only engages over the extended protocol /
 * ResultSetMetaData path exercised by jdbi's mapToMap/getObject in the real application.
 */
class EnumColumnOidResolutionTest {

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

    /** rs.getObject() on an enum column must not throw pgjdbc's castNonNull AssertionError. */
    @Test
    void getObject_on_enum_column_does_not_throw() throws SQLException {
        exec("CREATE TYPE ecor_mode AS ENUM ('manual', 'auto')");
        exec("CREATE TABLE ecor_installations (id int PRIMARY KEY, current_provider_id int, mode ecor_mode)");
        exec("INSERT INTO ecor_installations VALUES (1, 42, 'auto')");
        try {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT current_provider_id, mode FROM ecor_installations")) {
                assertTrue(rs.next());
                Object provider = rs.getObject("current_provider_id");
                Object mode = assertDoesNotThrow(() -> rs.getObject("mode"),
                        "rs.getObject(\"mode\") must not throw on a custom enum column");
                assertEquals(42, provider);
                assertEquals("auto", mode.toString());
            }
        } finally {
            exec("DROP TABLE IF EXISTS ecor_installations");
            exec("DROP TYPE IF EXISTS ecor_mode");
        }
    }

    /** ResultSetMetaData.getColumnTypeName() must resolve the enum's own type name, not throw. */
    @Test
    void getColumnTypeName_on_enum_column_resolves_type_name() throws SQLException {
        exec("CREATE TYPE ecor_status AS ENUM ('pending', 'done')");
        exec("CREATE TABLE ecor_tasks (id int PRIMARY KEY, status ecor_status)");
        exec("INSERT INTO ecor_tasks VALUES (1, 'pending')");
        try {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT status FROM ecor_tasks")) {
                ResultSetMetaData md = rs.getMetaData();
                String typeName = assertDoesNotThrow(() -> md.getColumnTypeName(1),
                        "getColumnTypeName must not throw for a custom enum column");
                assertEquals("ecor_status", typeName);
                assertTrue(rs.next());
                assertEquals("pending", rs.getString(1));
            }
        } finally {
            exec("DROP TABLE IF EXISTS ecor_tasks");
            exec("DROP TYPE IF EXISTS ecor_status");
        }
    }

    /** Two distinct enum types in the same result set must resolve to their own distinct names. */
    @Test
    void two_distinct_enum_columns_resolve_distinct_type_names() throws SQLException {
        exec("CREATE TYPE ecor_color AS ENUM ('red', 'blue')");
        exec("CREATE TYPE ecor_size AS ENUM ('small', 'large')");
        exec("CREATE TABLE ecor_items (id int PRIMARY KEY, color ecor_color, size ecor_size)");
        exec("INSERT INTO ecor_items VALUES (1, 'red', 'large')");
        try {
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT color, size FROM ecor_items")) {
                ResultSetMetaData md = rs.getMetaData();
                String colorType = assertDoesNotThrow(() -> md.getColumnTypeName(1));
                String sizeType = assertDoesNotThrow(() -> md.getColumnTypeName(2));
                assertEquals("ecor_color", colorType);
                assertEquals("ecor_size", sizeType);
                assertNotEquals(colorType, sizeType);
            }
        } finally {
            exec("DROP TABLE IF EXISTS ecor_items");
            exec("DROP TYPE IF EXISTS ecor_color");
            exec("DROP TYPE IF EXISTS ecor_size");
        }
    }
}
