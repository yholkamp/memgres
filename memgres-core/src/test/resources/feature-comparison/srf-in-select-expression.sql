-- Set-returning function (SRF) nested inside a larger SELECT-list expression.
--
-- Regression coverage for mtask-6 Bug 8: memgres already expanded a bare/top-level SRF in the
-- select list (e.g. `SELECT generate_series(0,23,2)`) to one row per element, but an SRF nested
-- inside arithmetic (e.g. `day_start + (interval '1 hour' * generate_series(0,23,2))`, the shape
-- used by TennetSettlementPricesDao.findMostRecentMissingTimestamp) was not detected, so the
-- whole expression tried to treat the raw generated list as a scalar operand.
-- PG 10+ ProjectSet semantics: an SRF anywhere in the select list expands the result to one row
-- per generated element, and every other target/column in that select list is (re)computed once
-- per element (or repeated verbatim if it doesn't reference the SRF).

-- setup
CREATE TABLE srf_expr_t (id int, day_start timestamp);
INSERT INTO srf_expr_t VALUES (1, '2024-01-01 00:00:00');

-- stmt 1: SRF nested in arithmetic (interval * generate_series) expands to one row per element
-- begin-expected
-- columns: ts
-- row: 2024-01-01 00:00:00
-- row: 2024-01-01 01:00:00
-- row: 2024-01-01 02:00:00
-- row: 2024-01-01 03:00:00
-- end-expected
SELECT day_start + (interval '1 hour' * generate_series(0, 3)) AS ts FROM srf_expr_t;

-- stmt 2: a sibling column not referencing the SRF repeats across the expanded rows
-- begin-expected
-- columns: id | ts
-- row: 1, 2024-01-01 00:00:00
-- row: 1, 2024-01-01 01:00:00
-- row: 1, 2024-01-01 02:00:00
-- row: 1, 2024-01-01 03:00:00
-- end-expected
SELECT id, day_start + (interval '1 hour' * generate_series(0, 3)) AS ts FROM srf_expr_t;

-- cleanup
DROP TABLE srf_expr_t;

-- stmt 3: bare top-level SRF in the select list (no FROM) still works after the generalization
-- begin-expected
-- columns: n
-- row: 1
-- row: 2
-- row: 3
-- end-expected
SELECT generate_series(1, 3) AS n;
