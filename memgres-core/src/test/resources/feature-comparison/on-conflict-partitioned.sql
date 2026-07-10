-- ON CONFLICT DO UPDATE / DO NOTHING must route through a partitioned table's partition-local
-- indexes instead of silently inserting duplicates, and ALTER TABLE ... ADD CONSTRAINT on an
-- already-partitioned parent must propagate to existing partitions.

-- setup
CREATE TABLE ocp_t (bucket_ts date, installation_id int, val int,
    PRIMARY KEY (bucket_ts, installation_id)) PARTITION BY RANGE (bucket_ts);
CREATE TABLE ocp_t_p1 PARTITION OF ocp_t FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
INSERT INTO ocp_t VALUES ('2024-01-01', 1, 10);
INSERT INTO ocp_t VALUES ('2024-01-01', 1, 20)
    ON CONFLICT (bucket_ts, installation_id) DO UPDATE SET val = EXCLUDED.val;

-- stmt 1: DO UPDATE updated in place -- still exactly one row, with the new value
-- begin-expected
-- columns: val | cnt
-- row: 20|1
-- end-expected
SELECT val, count(*) OVER () AS cnt FROM ocp_t;

-- stmt 2: a genuine duplicate on a plain INSERT still raises 23505 through the partition
-- begin-expected-error
-- sqlstate: 23505
-- end-expected-error
INSERT INTO ocp_t VALUES ('2024-01-01', 1, 30);

-- setup
INSERT INTO ocp_t VALUES ('2024-01-01', 1, 40)
    ON CONFLICT (bucket_ts, installation_id) DO NOTHING;

-- stmt 3: DO NOTHING skipped the conflicting row without duplicating it
-- begin-expected
-- columns: val | cnt
-- row: 20|1
-- end-expected
SELECT val, count(*) OVER () AS cnt FROM ocp_t;

-- setup: constraint added only after the partition already exists
CREATE TABLE ocp_late (bucket_ts date, installation_id int, val int) PARTITION BY RANGE (bucket_ts);
CREATE TABLE ocp_late_p1 PARTITION OF ocp_late FOR VALUES FROM ('2024-01-01') TO ('2024-02-01');
ALTER TABLE ocp_late ADD CONSTRAINT ocp_late_pkey PRIMARY KEY (bucket_ts, installation_id);
INSERT INTO ocp_late VALUES ('2024-01-01', 7, 1);

-- stmt 4: the pre-existing partition enforces the late-added primary key
-- begin-expected-error
-- sqlstate: 23505
-- end-expected-error
INSERT INTO ocp_late VALUES ('2024-01-01', 7, 2);

-- setup
INSERT INTO ocp_late VALUES ('2024-01-01', 7, 5)
    ON CONFLICT (bucket_ts, installation_id) DO UPDATE SET val = EXCLUDED.val;

-- stmt 5: ON CONFLICT DO UPDATE also resolves against the late-added constraint
-- begin-expected
-- columns: val | cnt
-- row: 5|1
-- end-expected
SELECT val, count(*) OVER () AS cnt FROM ocp_late;

-- cleanup
DROP TABLE ocp_t;
DROP TABLE ocp_late;
