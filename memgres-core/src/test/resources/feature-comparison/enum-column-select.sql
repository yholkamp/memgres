-- Custom enum columns: select value round-trip, including a second column read
-- alongside the enum column (mirrors InstallationsApiControllerTest's
-- `SELECT current_provider_id, mode FROM installations`).
--
-- Regression coverage for mtask-6 Bug 7: pgjdbc resolves unknown RowDescription
-- type OIDs via its TypeInfoCache (a client-side pg_type lookup) whenever it
-- needs a Java type mapping for a column — e.g. ResultSet.getObject() or
-- ResultSetMetaData.getColumnTypeName(). memgres previously advertised the
-- generic DataType.ENUM placeholder OID (0) for every enum column instead of
-- the enum type's own dynamically-allocated OID, which pgjdbc's TypeInfoCache
-- treats as Oid.UNSPECIFIED and refuses to resolve — crashing client-side with
-- `AssertionError: Misuse of castNonNull` in PgResultSet.initSqlType. That
-- specific crash is a JDBC client-API-level failure (triggered by getObject()/
-- getColumnTypeName(), not by reading column values as text), so it is
-- exercised directly by EnumColumnOidResolutionTest via real pgjdbc; this file
-- covers the SQL-visible value semantics (enum values round-trip as their
-- label text, ordering/comparison still work) that a PG-vs-Memgres diff run
-- would also want to confirm.

-- setup
CREATE TYPE ecs_mode AS ENUM ('manual', 'auto');
CREATE TABLE ecs_installations (id int PRIMARY KEY, current_provider_id int, mode ecs_mode);
INSERT INTO ecs_installations VALUES (1, 42, 'auto'), (2, 7, 'manual');

-- stmt 1: selecting an int column alongside an enum column
-- begin-expected
-- columns: current_provider_id | mode
-- row: 42, auto
-- end-expected
SELECT current_provider_id, mode FROM ecs_installations WHERE id = 1;

-- stmt 2: enum value round-trips as its label text
-- begin-expected
-- columns: mode
-- row: manual
-- end-expected
SELECT mode FROM ecs_installations WHERE id = 2;

-- stmt 3: enum ordinal comparison still works after the OID fix
-- begin-expected
-- columns: count
-- row: 1
-- end-expected
SELECT count(*) FROM ecs_installations WHERE mode > 'manual';

-- cleanup
DROP TABLE ecs_installations;
DROP TYPE ecs_mode;
