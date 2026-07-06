-- WITH ORDINALITY on timestamptz generate_series, and the SRF-provenance gate on the
-- attribute-notation fallback: the ordinality column exists only when WITH ORDINALITY is
-- present, and alias.name-as-function-call resolution applies only to FROM-function aliases,
-- never to plain single-column subquery aliases.

-- stmt 1: WITH ORDINALITY exposes a 1-based ordinality column
-- begin-expected
-- columns: d | n
-- row: 2026-01-01|1
-- row: 2026-01-02|2
-- row: 2026-01-03|3
-- end-expected
SELECT gs.key::date AS d, gs.ord AS n
FROM generate_series('2026-01-01'::timestamptz, '2026-01-03'::timestamptz, '1 day'::interval)
     WITH ORDINALITY AS gs(key, ord)
ORDER BY gs.ord;

-- stmt 2: without WITH ORDINALITY there is no ordinality column
-- begin-expected-error
-- sqlstate: 42703
-- end-expected-error
SELECT gs.ordinality
FROM generate_series('2026-01-01'::timestamptz, '2026-01-02'::timestamptz, '1 day'::interval) AS gs(key);

-- stmt 3: attribute notation stays gated on SRF provenance -- a plain single-column
-- subquery alias does not resolve t.date as date(t)
-- begin-expected-error
-- sqlstate: 42703
-- end-expected-error
SELECT t.date FROM (SELECT '2026-01-01'::timestamptz AS key) AS t;
