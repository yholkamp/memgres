-- Set-returning function (SRF) used directly as a SELECT-list target: the result column's
-- advertised type must be the SRF's element type, not TEXT.
--
-- Regression coverage for mtask-8 Group 2: ExprEvaluator.inferTypeFromContext had no case for
-- generate_series (or AT TIME ZONE), so a bare SELECT-list `generate_series(...)` fell through
-- to the default TEXT type in RowDescription. pgjdbc's strict getObject(col, LocalDate.class) /
-- getTimestamp then rejected the type/value mismatch -- exactly the months-CTE shape in
-- ResultsMonthlyDao (`WITH months AS (SELECT generate_series(date_trunc('month', ... AT TIME
-- ZONE ...), ..., interval '1 month') AS month) ...`).

-- stmt 1: bare SELECT-list generate_series over timestamptz
-- begin-expected
-- columns: month
-- row: 2024-01-01 00:00:00+00
-- row: 2024-02-01 00:00:00+00
-- row: 2024-03-01 00:00:00+00
-- end-expected
SELECT generate_series(timestamptz '2024-01-01 UTC', timestamptz '2024-03-01 UTC', interval '1 month') AS month;

-- stmt 2: the months-CTE shape (date_trunc + AT TIME ZONE feeding generate_series), wrapped and
-- re-selected through a CTE, as ResultsMonthlyDao does
-- begin-expected
-- columns: date
-- row: 2024-01-01 00:00:00
-- row: 2024-02-01 00:00:00
-- row: 2024-03-01 00:00:00
-- end-expected
WITH months AS (
  SELECT generate_series(
      date_trunc('month', timestamptz '2024-01-15 UTC' AT TIME ZONE 'UTC'),
      date_trunc('month', timestamptz '2024-03-15 UTC' AT TIME ZONE 'UTC'),
      interval '1 month') AS month
) SELECT m.month AS date FROM months m ORDER BY m.month;

-- stmt 3: integer generate_series in the select list is unaffected (regression guard)
-- begin-expected
-- columns: n
-- row: 1
-- row: 2
-- row: 3
-- end-expected
SELECT generate_series(1, 3) AS n;
