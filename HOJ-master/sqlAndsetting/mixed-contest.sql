-- Existing installations: apply after paper.sql and paper-judge.sql, once.
CREATE TABLE IF NOT EXISTS contest_paper (
    contest_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    rules_json TEXT NOT NULL,
    template_json LONGTEXT NOT NULL,
    CONSTRAINT fk_contest_paper_contest FOREIGN KEY (contest_id) REFERENCES contest(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE paper_run ADD COLUMN contest_id BIGINT NULL;
ALTER TABLE paper_run ADD UNIQUE KEY uk_paper_run_contest (contest_id, uid);
