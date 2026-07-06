-- Temporal columns compared against untyped text operands (relational operators).
--
-- Regression coverage for mtask-8 Group 3: an unknown-type text operand (a bound parameter PG
-- hasn't resolved a concrete type for, or an ISO literal) compared against a stored
-- timestamp/timestamptz column fell through to lexicographic string comparison
-- (TypeCoercion.pgStringCompare), which silently miscompares ISO 'T'-separated temporal text
-- against PG's ' '-separated format ('T' > ' ' in ASCII) -- every stored value compared greater
-- than every text operand regardless of actual instant. <, <=, BETWEEN were always false; >, >=
-- always true. Silent wrong results, not an error.

-- setup
CREATE TABLE tstz_cmp_t (id int PRIMARY KEY, ts timestamptz);
INSERT INTO tstz_cmp_t VALUES (1, '2026-01-13 00:00:00+00');
INSERT INTO tstz_cmp_t VALUES (2, '2026-01-13 12:00:00+00');
INSERT INTO tstz_cmp_t VALUES (3, '2026-01-14 00:00:00+00');

-- stmt 1: '<' against a text literal correctly excludes later rows
-- begin-expected
-- columns: id
-- row: 1
-- end-expected
SELECT id FROM tstz_cmp_t WHERE ts < '2026-01-13 12:00:00+00' ORDER BY id;

-- stmt 2: '>=' against a text literal correctly includes the boundary and later rows
-- begin-expected
-- columns: id
-- row: 2
-- row: 3
-- end-expected
SELECT id FROM tstz_cmp_t WHERE ts >= '2026-01-13 12:00:00+00' ORDER BY id;

-- stmt 3: BETWEEN with text literal bounds
-- begin-expected
-- columns: id
-- row: 1
-- row: 2
-- end-expected
SELECT id FROM tstz_cmp_t WHERE ts BETWEEN '2026-01-13 00:00:00+00' AND '2026-01-13 23:59:59+00' ORDER BY id;

-- stmt 4: unparseable text operand raises an invalid-datetime error, not a silent wrong compare
-- begin-expected-error
-- sqlstate: 22007
-- message-like: invalid input syntax
-- end-expected-error
SELECT id FROM tstz_cmp_t WHERE ts < 'not-a-timestamp';

-- cleanup
DROP TABLE tstz_cmp_t;
