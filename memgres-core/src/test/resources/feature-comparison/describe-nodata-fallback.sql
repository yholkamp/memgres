-- pgjdbc drives every statement through the extended protocol (Parse/Bind/Describe/Execute),
-- so these statements exercise Describe-time inference: a WITH query whose string literal
-- contains parens/keywords must still be classified as row-returning, generate_series must
-- describe null-safely, and a genuinely broken query must surface its error instead of a
-- silent NoData. Protocol-level named-statement coverage (prepareThreshold=1) lives in
-- DescribeNoDataFallbackTest.

-- setup
CREATE TABLE ddf_t (id int PRIMARY KEY, v text);
INSERT INTO ddf_t VALUES (1, 'a');

-- stmt 1: WITH ... SELECT whose string literal contains ') DELETE' is still row-returning
-- begin-expected
-- columns: s
-- row: ) DELETE
-- end-expected
WITH x AS (SELECT ') DELETE' AS s) SELECT s FROM x;

-- stmt 2: months CTE over timestamptz generate_series describes and returns rows
-- begin-expected
-- columns: n
-- row: 3
-- end-expected
WITH months AS (
  SELECT generate_series('2024-01-01'::timestamptz, '2024-03-01'::timestamptz,
                         interval '1 month') AS month
)
SELECT count(*) AS n FROM months;

-- stmt 3: generate_series with NULL bounds describes null-safely and yields no rows
-- begin-expected
-- columns: n
-- row: 0
-- end-expected
SELECT count(*) AS n
FROM generate_series(NULL::timestamptz, NULL::timestamptz, NULL::interval) AS gs(m);

-- stmt 4: a broken column reference surfaces 42703 instead of a silent no-data result
-- begin-expected-error
-- sqlstate: 42703
-- end-expected-error
SELECT nonexistent_column FROM ddf_t WHERE id = 1;

-- cleanup
DROP TABLE ddf_t;
