-- An ENUM column populated via its column DEFAULT must compare by declared enum order, not
-- lexicographic string order.
--
-- Regression coverage for mtask-8 Group 6: DmlExecutor's enum-label validation/PgEnum-wrapping
-- step (which makes <, <=, >, >=, ORDER BY, simple CASE use PG's declaration order) ran only over
-- explicitly-provided INSERT columns; a column filled via its DEFAULT clause stayed a raw string
-- forever, so ordering comparisons against it silently fell back to lexicographic string
-- comparison. This is what made a real partial-unique-index predicate
-- (`WHERE queue_name = 'price_check' AND state < 'active'`) silently evaluate false for a
-- newly-DEFAULT-'created' row, letting a duplicate row slip through ON CONFLICT DO NOTHING.

-- setup
CREATE TYPE edvo_state AS ENUM ('created', 'retry', 'active', 'completed');
CREATE TABLE edvo_t (id int, state edvo_state NOT NULL DEFAULT 'created');
INSERT INTO edvo_t (id) VALUES (1);

-- stmt 1: 'created' (the DEFAULT) sorts before 'active' by declared enum order, not string order
-- begin-expected
-- columns: lt
-- row: t
-- end-expected
SELECT state < 'active' AS lt FROM edvo_t;

-- cleanup
DROP TABLE edvo_t;
DROP TYPE edvo_state;
