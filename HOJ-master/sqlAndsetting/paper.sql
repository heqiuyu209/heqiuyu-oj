-- Mixed papers reference existing objective and programming questions.
CREATE TABLE IF NOT EXISTS paper (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content_json LONGTEXT NOT NULL,
    version INT NOT NULL,
    published TINYINT(1) NOT NULL DEFAULT 0,
    created_by VARCHAR(32) NOT NULL,
    gmt_create DATETIME(3) NOT NULL,
    gmt_modified DATETIME(3) NOT NULL,
    KEY idx_paper_owner (created_by, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paper_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    paper_version INT NOT NULL,
    contest_id BIGINT NULL,
    virtual_run_id BIGINT UNSIGNED NULL,
    uid VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    answers_json LONGTEXT NOT NULL,
    gmt_create DATETIME(3) NOT NULL,
    gmt_modified DATETIME(3) NOT NULL,
    UNIQUE KEY uk_paper_run_request (uid, request_id),
    UNIQUE KEY uk_paper_run_contest (contest_id, uid),
    UNIQUE KEY uk_paper_run_virtual (virtual_run_id),
    KEY idx_paper_run_user (uid, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS paper_program_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    item_key VARCHAR(64) NOT NULL,
    uid VARCHAR(32) NOT NULL,
    content_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_paper_program_snapshot (run_id, item_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

