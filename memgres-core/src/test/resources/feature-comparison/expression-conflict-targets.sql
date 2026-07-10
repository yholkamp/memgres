-- Expression conflict targets: parenthesized expressions in ON CONFLICT targets and their
-- matching CREATE UNIQUE INDEX arbiter, mixed with plain columns. Both constructs here are valid,
-- standard PostgreSQL (expressions are legal in CREATE UNIQUE INDEX and in ON CONFLICT target
-- lists) -- this file stays in the strict PG-diff comparison set.
--
-- Regression coverage for mtask-6 Bug 5: DmlParser.parseOnConflict previously accepted only bare
-- identifiers in a conflict-target column list, so a parenthesized expression anywhere in the
-- list (e.g. `ON CONFLICT (queue_name, ((input->>'price_id')))`) threw 42601 "Expected identifier".
--
-- Note: the table-level `UNIQUE (id, (data->>'k'))` *constraint* form (as opposed to
-- `CREATE UNIQUE INDEX ... (id, (data->>'k'))`) is not valid PostgreSQL syntax (42601 --
-- expressions are only legal in CREATE UNIQUE INDEX); memgres's Bug 5 fix additionally supports it
-- as a deliberate extension, covered separately in
-- expression-unique-table-constraint-memgres-extension.sql (excluded from PG-diff parity).
--
-- Mirrors the real-world case in JobDao.insertPriceCheckJob / the
-- jobs_price_check_one_pending partial unique index (double-parenthesized
-- expression target with a WHERE predicate).

-- setup: ON CONFLICT with a mixed column/expression target list, matching
-- a partial unique index over the identical target (real-world JobDao shape).
-- state is a real enum (mirrors jobs.state/job_state in V45__job_queue.sql) so
-- "state < 'active'" compares by declared enum ordinal ('created' < 'active'),
-- not lexical string order (where "created" > "active").
CREATE TYPE ect_job_state AS ENUM ('created', 'retry', 'active', 'completed');
CREATE TABLE ect_jobs_t (id serial PRIMARY KEY, queue_name text, input jsonb, state ect_job_state);
CREATE UNIQUE INDEX ect_jobs_one_pending
    ON ect_jobs_t (queue_name, ((input->>'price_id')))
    WHERE queue_name = 'price_check' AND state < 'active';

INSERT INTO ect_jobs_t (queue_name, input, state)
    VALUES ('price_check', '{"price_id":"p1"}', 'created');

-- stmt 1: a conflicting pending job for the same price_id is skipped (DO NOTHING)
INSERT INTO ect_jobs_t (queue_name, input, state)
    VALUES ('price_check', '{"price_id":"p1"}', 'created')
    ON CONFLICT (queue_name, ((input->>'price_id')))
      WHERE queue_name = 'price_check' AND state < 'active'
    DO NOTHING;

-- begin-expected
-- columns: count
-- row: 1
-- end-expected
SELECT count(*) FROM ect_jobs_t;

-- stmt 2: a distinct price_id is not a conflict and inserts normally
INSERT INTO ect_jobs_t (queue_name, input, state)
    VALUES ('price_check', '{"price_id":"p2"}', 'created')
    ON CONFLICT (queue_name, ((input->>'price_id')))
      WHERE queue_name = 'price_check' AND state < 'active'
    DO NOTHING;

-- begin-expected
-- columns: count
-- row: 2
-- end-expected
SELECT count(*) FROM ect_jobs_t;

-- cleanup
DROP TABLE ect_jobs_t;
DROP TYPE ect_job_state;
