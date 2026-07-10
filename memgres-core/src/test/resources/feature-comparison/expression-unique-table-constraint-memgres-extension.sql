-- MEMGRES EXTENSION -- not standard PostgreSQL, deliberately excluded from strict PG-diff parity.
--
-- Real PostgreSQL rejects a parenthesized expression inside a table-level UNIQUE constraint with
-- 42601 ("syntax error"): expressions are only legal in CREATE UNIQUE INDEX, e.g.
-- `UNIQUE (id, (data->>'k'))` as an inline/out-of-line table constraint is not valid PG syntax
-- (the equivalent PG-valid form is `CREATE UNIQUE INDEX ... ON t (id, (data->>'k'))`, covered by
-- expression-conflict-targets.sql). memgres's mtask-6 Bug 5 fix additionally accepted expressions
-- in the table-constraint form as a convenience extension (DdlTableParser's inline UNIQUE
-- handling, enforced via row-scan `ConstraintValidator.validateUniqueness`). This file documents
-- that deliberate divergence so it isn't mistaken for a PG-parity gap when comparing against a
-- real PostgreSQL instance -- do not add this construct to a PG-diff-tracked file.
--
-- Every statement carries a `-- pg-bug:` skip annotation: FeatureComparisonReport discovers all
-- *.sql files in this directory unconditionally and doesn't parse prose headers, so without the
-- per-statement skip the CREATE TABLE (and everything downstream of it, since the table never
-- exists on PG) would be counted as PG-vs-memgres divergences. The construct is a deliberate
-- memgres extension, not a PG bug, but `-- pg-bug:` is the report tool's only per-statement
-- exclusion mechanism.

-- setup
-- pg-bug: Deliberate memgres extension — expression in a table-level UNIQUE constraint; PG raises 42601 (expressions are only supported via CREATE UNIQUE INDEX)
CREATE TABLE ect_ext_t (id int, data jsonb, UNIQUE (id, (data->>'k')));

-- stmt 1: mixed column/expression UNIQUE constraint accepts a first row
-- pg-bug: Downstream of the memgres-extension CREATE TABLE above — ect_ext_t does not exist on PG
INSERT INTO ect_ext_t (id, data) VALUES (1, '{"k":"a"}');

-- pg-bug: Downstream of the memgres-extension CREATE TABLE above — ect_ext_t does not exist on PG
-- begin-expected
-- columns: count
-- row: 1
-- end-expected
SELECT count(*) FROM ect_ext_t;

-- stmt 2: duplicate (id, data->>'k') violates the mixed unique constraint
-- pg-bug: Downstream of the memgres-extension CREATE TABLE above — ect_ext_t does not exist on PG
-- begin-expected-error
-- sqlstate: 23505
-- message-like: duplicate key value violates unique constraint
-- end-expected-error
INSERT INTO ect_ext_t (id, data) VALUES (1, '{"k":"a"}');

-- stmt 3: same id but a different expression value is allowed
-- pg-bug: Downstream of the memgres-extension CREATE TABLE above — ect_ext_t does not exist on PG
INSERT INTO ect_ext_t (id, data) VALUES (1, '{"k":"b"}');

-- pg-bug: Downstream of the memgres-extension CREATE TABLE above — ect_ext_t does not exist on PG
-- begin-expected
-- columns: count
-- row: 2
-- end-expected
SELECT count(*) FROM ect_ext_t;

-- cleanup
-- pg-bug: Downstream of the memgres-extension CREATE TABLE above — ect_ext_t does not exist on PG
DROP TABLE ect_ext_t;
