-- Apply once when upgrading an existing database, after paper.sql.
ALTER TABLE judge
    ADD COLUMN paper_run_id BIGINT NULL,
    ADD COLUMN paper_snapshot_id BIGINT NULL,
    ADD KEY idx_judge_paper (paper_run_id, paper_snapshot_id, submit_id);

