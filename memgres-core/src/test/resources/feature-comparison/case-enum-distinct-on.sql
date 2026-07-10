-- Simple CASE over an enum column + DISTINCT ON / ORDER BY ... LIMIT 1 row selection.
--
-- Regression coverage for mtask-6 Bug 10: simple CASE matching used raw Java equality, so
-- CASE <enum_col> WHEN 'label' never matched (a stored PgEnum value is not Object-equal to the
-- WHEN's String label) and every row fell through to ELSE. That made ranking expressions like
-- ORDER BY CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END
-- a constant, so DISTINCT ON (and ORDER BY ... LIMIT 1) silently returned an arbitrary
-- (insertion-order) row instead of the highest-priority one — the app's
-- ResultsMonthlyDao.findKwhFallback / ResultsDailyDao DISTINCT ON priority pattern.
-- PG defines simple CASE as "operand = whenValue", so it must use = operator semantics.

-- setup
CREATE TYPE ced_result_type AS ENUM ('direct', 'manual', 'api', 'auto');
CREATE TABLE ced_rm (installation_id int, date date, type ced_result_type, battery_charged int);
-- 'auto' (rank 3) inserted BEFORE 'manual' (rank 1): insertion order must not win.
INSERT INTO ced_rm VALUES (1, '2024-01-01', 'auto', 999), (1, '2024-01-01', 'manual', 111);

-- stmt 1: simple CASE over the enum column matches its labels
-- begin-expected
-- columns: type | rank
-- row: manual, 1
-- row: auto, 3
-- end-expected
SELECT type, CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END AS rank
FROM ced_rm ORDER BY type;

-- stmt 2: DISTINCT ON with a CASE-over-enum ranking key returns the highest-priority row
-- begin-expected
-- columns: battery_charged
-- row: 111
-- end-expected
SELECT DISTINCT ON (installation_id) battery_charged
FROM ced_rm
ORDER BY installation_id,
  CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END;

-- stmt 3: the findKwhFallback shape — scalar subquery with ORDER BY CASE ... LIMIT 1
-- begin-expected
-- columns: battery_charged
-- row: 111
-- end-expected
SELECT (SELECT battery_charged FROM ced_rm
         WHERE installation_id = 1 AND date = '2024-01-01'
           AND type <> 'api' AND battery_charged IS NOT NULL
         ORDER BY CASE type WHEN 'direct' THEN 0 WHEN 'manual' THEN 1 WHEN 'api' THEN 2 ELSE 3 END
         LIMIT 1) AS battery_charged;

-- cleanup
DROP TABLE ced_rm;
DROP TYPE ced_result_type;
