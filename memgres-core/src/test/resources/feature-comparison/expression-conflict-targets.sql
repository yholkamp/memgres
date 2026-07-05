-- Expression conflict targets: parenthesized expressions in UNIQUE constraint
-- column lists and ON CONFLICT targets, mixed with plain columns.
--
-- Regression coverage for mtask-6 Bug 5: DdlTableParser's inline UNIQUE
-- handling and DmlParser.parseOnConflict previously accepted only bare
-- identifiers in a column list, so a parenthesized expression anywhere in
-- the list (e.g. `UNIQUE (id, (data->>'k'))` or
-- `ON CONFLICT (queue_name, ((input->>'price_id')))`) threw
-- 42601 "Expected identifier".
--
-- Mirrors the real-world case in JobDao.insertPriceCheckJob / the
-- jobs_price_check_one_pending partial unique index (double-parenthesized
-- expression target with a WHERE predicate).

-- setup
CREATE TABLE ect_t (id int, data jsonb, UNIQUE (id, (data->>'k')));

-- stmt 1: mixed column/expression UNIQUE constraint accepts a first row
INSERT INTO ect_t (id, data) VALUES (1, '{"k":"a"}');

-- begin-expected
-- columns: count
-- row: 1
-- end-expected
SELECT count(*) FROM ect_t;

-- stmt 2: duplicate (id, data->>'k') violates the mixed unique constraint
-- begin-expected-error
-- sqlstate: 23505
-- message-like: duplicate key value violates unique constraint
-- end-expected-error
INSERT INTO ect_t (id, data) VALUES (1, '{"k":"a"}');

-- stmt 3: same id but a different expression value is allowed
INSERT INTO ect_t (id, data) VALUES (1, '{"k":"b"}');

-- begin-expected
-- columns: count
-- row: 2
-- end-expected
SELECT count(*) FROM ect_t;

-- cleanup
DROP TABLE ect_t;

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

-- stmt 4: a conflicting pending job for the same price_id is skipped (DO NOTHING)
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

-- stmt 5: a distinct price_id is not a conflict and inserts normally
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
