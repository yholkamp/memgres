-- The projected column type for a qualified reference resolved via the attribute-notation
-- fallback (alias.name == name(alias), e.g. gs.date == date(gs) for a single-column
-- `generate_series(...) AS gs(key)` binding) must be the cast's target type, not TEXT.
--
-- Regression coverage for mtask-8 Group 7: ExprEvaluator.inferTypeFromContext's ColumnRef branch
-- only ever looked up ref.column() as a real column and defaulted to TEXT otherwise, with no
-- awareness of the attribute-notation fallback the runtime evaluator (tryAttributeNotationFallback,
-- mtask-3) already resolves gs.date through. So gs.date always advertised TEXT in RowDescription,
-- and pgjdbc's strict getObject(col, LocalDate.class)/getDate rejected the value/type mismatch --
-- exactly ResultsDailyDao.listByInstallationIdAndDate's shape.

-- setup: force a deterministic session TimeZone so the zoneless timestamptz literals below
-- interpret and render consistently regardless of the JVM's / PG server's default zone.
SET TIME ZONE 'UTC';

-- stmt 1: gs.date resolves via attribute notation; its value round-trips as a date
-- begin-expected
-- columns: key
-- row: 2026-01-01
-- row: 2026-01-02
-- row: 2026-01-03
-- end-expected
SELECT gs.date AS key
FROM generate_series('2026-01-01'::timestamptz, '2026-01-03'::timestamptz, '1 day'::interval) AS gs(key);

-- stmt 2: the real column still wins over the fallback (column-wins semantics in inference too)
-- begin-expected
-- columns: key
-- row: 2026-01-01 00:00:00+00
-- row: 2026-01-02 00:00:00+00
-- row: 2026-01-03 00:00:00+00
-- end-expected
SELECT gs.key
FROM generate_series('2026-01-01'::timestamptz, '2026-01-03'::timestamptz, '1 day'::interval) AS gs(key);

-- cleanup: don't leak the session TimeZone override into files that run after this one.
RESET TimeZone;
