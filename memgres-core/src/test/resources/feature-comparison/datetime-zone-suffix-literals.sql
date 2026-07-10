-- Trailing named-timezone suffixes in datetime literals (zone abbreviations and IANA region
-- names, with or without seconds in the time-of-day), and SET TIME ZONE governing both
-- interpretation and rendering of zoneless timestamptz casts.

-- setup
SET TIME ZONE 'UTC';

-- stmt 1: date-only literal with a trailing zone abbreviation
-- begin-expected
-- columns: t
-- row: 2024-01-01 00:00:00+00
-- end-expected
SELECT timestamptz '2024-01-01 UTC' AS t;

-- stmt 2: HH:MM time (no seconds) with a zone abbreviation
-- begin-expected
-- columns: t
-- row: 2024-02-11 11:00:00+00
-- end-expected
SELECT timestamptz '2024-02-11 12:00 CET' AS t;

-- stmt 3: full IANA region name as the zone suffix
-- begin-expected
-- columns: t
-- row: 2024-02-11 11:00:00+00
-- end-expected
SELECT timestamptz '2024-02-11 12:00 Europe/Amsterdam' AS t;

-- stmt 4: timestamp without time zone parses the trailing zone but ignores it
-- begin-expected
-- columns: t
-- row: 2024-01-01 12:00:00
-- end-expected
SELECT timestamp '2024-01-01 12:00 CET' AS t;

-- setup: a zoneless timestamptz cast is interpreted in the session zone at insert time
CREATE TABLE dzsl_t (t timestamptz);
SET TIME ZONE 'America/New_York';
INSERT INTO dzsl_t VALUES ('2026-01-01'::timestamptz);
SET TIME ZONE 'UTC';

-- stmt 5: the stored instant reflects Eastern-time interpretation, not the server JVM zone
-- begin-expected
-- columns: t
-- row: 2026-01-01 05:00:00+00
-- end-expected
SELECT t FROM dzsl_t;

-- cleanup
DROP TABLE dzsl_t;
RESET TimeZone;
