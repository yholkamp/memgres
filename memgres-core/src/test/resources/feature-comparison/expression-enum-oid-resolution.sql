-- A projection-built expression that infers ENUM (COALESCE over an enum column, a CASE
-- branching to an explicit enum cast, ...) must advertise its concrete enum type name, not the
-- generic ENUM placeholder.
--
-- Regression coverage for mtask-8 C1: the mtask-6 Bug 7 fix resolves the real per-type OID via
-- Column.getEnumTypeName(), which is only populated for a plain column reference. An expression
-- had no Column to carry that name from, so it fell back to the ENUM placeholder OID 0 -- the
-- exact castNonNull crash Bug 7 fixed for plain columns, reopened for expressions. This file only
-- exercises the values/columns the SqlVerifyTest harness can check (it runs over jdbi/JDBC without
-- exposing raw wire-level OIDs); ExpressionEnumOidResolutionTest covers the actual pgjdbc
-- getObject/getColumnTypeName repro end-to-end.

-- setup
CREATE TYPE eeor_v_mode AS ENUM ('manual', 'auto');
CREATE TABLE eeor_v_installations (id int PRIMARY KEY, mode eeor_v_mode);
INSERT INTO eeor_v_installations VALUES (1, NULL);
INSERT INTO eeor_v_installations VALUES (2, 'auto');

-- stmt 1: COALESCE over an enum column with a literal default
-- begin-expected
-- columns: coalesce
-- row: manual
-- row: auto
-- end-expected
SELECT COALESCE(mode, 'manual') FROM eeor_v_installations ORDER BY id;

-- cleanup
DROP TABLE eeor_v_installations;
DROP TYPE eeor_v_mode;
