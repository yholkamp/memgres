-- Casting an array literal must strip element quotes, honor backslash escapes inside quoted
-- elements, and keep the raw text of unquoted elements (no numeric re-normalization).

-- setup
CREATE TYPE alcq_mood AS ENUM ('sad', 'ok', 'happy');

-- stmt 1: quoted elements cast to enum[] lose their quotes
-- begin-expected
-- columns: e1 | e2
-- row: sad|happy
-- end-expected
SELECT ('{"sad","happy"}'::alcq_mood[])[1] AS e1, ('{"sad","happy"}'::alcq_mood[])[2] AS e2;

-- stmt 2: unquoted elements cast to text[]
-- begin-expected
-- columns: e1 | e2
-- row: api|web
-- end-expected
SELECT ('{api,web}'::text[])[1] AS e1, ('{api,web}'::text[])[2] AS e2;

-- stmt 3: escaped quote and escaped backslash inside quoted elements
-- begin-expected
-- columns: e1 | e2
-- row: a"b|c\d
-- end-expected
SELECT ('{"a\"b","c\\d"}'::text[])[1] AS e1, ('{"a\"b","c\\d"}'::text[])[2] AS e2;

-- stmt 4: quoted comma and braces stay part of the element text
-- begin-expected
-- columns: e1 | e2
-- row: a,b|{x}
-- end-expected
SELECT ('{"a,b","{x}"}'::text[])[1] AS e1, ('{"a,b","{x}"}'::text[])[2] AS e2;

-- stmt 5: unquoted NULL is SQL NULL, quoted "NULL" is the four-character string
-- begin-expected
-- columns: e1 | e2
-- row: null|NULL
-- end-expected
SELECT ('{NULL}'::text[])[1] AS e1, ('{"NULL"}'::text[])[1] AS e2;

-- stmt 6: unquoted numeric-looking elements keep their exact text when cast to text[]
-- begin-expected
-- columns: e1 | e2
-- row: 01|02
-- end-expected
SELECT ('{01,02}'::text[])[1] AS e1, ('{01,02}'::text[])[2] AS e2;

-- stmt 7: quoted enum elements work as ANY() membership targets
-- begin-expected
-- columns: m
-- row: t
-- end-expected
SELECT 'sad'::alcq_mood = ANY('{"sad","ok"}'::alcq_mood[]) AS m;

-- cleanup
DROP TYPE alcq_mood;
