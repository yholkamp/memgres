-- Attribute-notation fallback (alias.name == name(alias)) for single-column FROM-function
-- aliases, when the query also has a JOIN.
--
-- Regression coverage for mtask-8 Group 1: mtask-3 added the attribute-notation fallback (e.g.
-- gs.date == date(gs) for `FROM generate_series(...) AS gs(key)`), but a separate, older
-- column-validation loop in SelectExecutor runs whenever the query has any JOIN and rejects any
-- qualified SELECT-list ColumnRef it can't find as a literal column, before the fallback ever
-- gets a chance to run. This is exactly ResultsDailyDao.listByInstallationIdAndDate's shape:
-- `FROM generate_series(...) AS gs(key) LEFT JOIN results_daily rd ON rd.d = gs.date`.

-- setup
CREATE TABLE jan_rd (d date, v int);
INSERT INTO jan_rd VALUES ('2026-01-02', 42);

-- stmt 1: gs.date resolves via attribute notation even though the query has a JOIN
-- begin-expected
-- columns: key | v
-- row: 2026-01-01, NULL
-- row: 2026-01-02, 42
-- row: 2026-01-03, NULL
-- end-expected
SELECT DISTINCT ON (gs.date) gs.date AS key, jan_rd.v
FROM generate_series('2026-01-01'::timestamptz, '2026-01-03'::timestamptz, '1 day'::interval) AS gs(key)
LEFT JOIN jan_rd ON jan_rd.d = gs.date
ORDER BY gs.date;

-- stmt 2: a genuinely unknown qualified column in a joined query still raises 42703
-- begin-expected-error
-- sqlstate: 42703
-- message-like: nonexistent_thing
-- end-expected-error
SELECT gs.nonexistent_thing
FROM generate_series('2026-01-01'::timestamptz, '2026-01-02'::timestamptz, '1 day'::interval) AS gs(key)
LEFT JOIN jan_rd ON jan_rd.d = gs.key::date;

-- cleanup
DROP TABLE jan_rd;
