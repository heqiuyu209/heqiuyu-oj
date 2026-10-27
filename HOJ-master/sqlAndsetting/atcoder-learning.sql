-- AtCoder public learning evidence; no remote-login credentials. Repeatable and additive.
CREATE TABLE IF NOT EXISTS external_atcoder_cursor (
 account_id BIGINT NOT NULL PRIMARY KEY,
 from_second BIGINT NOT NULL DEFAULT 0,
 scan_start_second BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_atcoder_cursor_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_atcoder_rating (
 account_id BIGINT NOT NULL,
 contest_id VARCHAR(80) NOT NULL,
 title VARCHAR(255) NOT NULL,
 contest_rank INT NOT NULL,
 old_rating INT NOT NULL,
 new_rating INT NOT NULL,
 occurred_at BIGINT NOT NULL,
 PRIMARY KEY(account_id,contest_id),
 CONSTRAINT fk_atcoder_rating_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_atcoder_problem (
 problem_key VARCHAR(120) NOT NULL PRIMARY KEY,
 task_id VARCHAR(80) NOT NULL,
 contest_id VARCHAR(80) NOT NULL,
 problem_index VARCHAR(32) NOT NULL,
 title VARCHAR(255) NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 available TINYINT(1) NOT NULL DEFAULT 1,
 seen_token VARCHAR(36) NOT NULL,
 last_seen_at DATETIME(3) NOT NULL,
 changed_at DATETIME(3) NOT NULL,
 raw_difficulty DOUBLE NULL,
 estimated_difficulty INT NULL,
 model_experimental TINYINT(1) NULL,
 model_updated_at DATETIME(3) NULL,
 UNIQUE KEY uk_atcoder_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO remote_source_job(source_name,next_run_at) VALUES('ATCODER_PROBLEMS',CURRENT_TIMESTAMP(3));
INSERT IGNORE INTO remote_source_job(source_name,next_run_at) VALUES('ATCODER_MODELS',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 5 SECOND));
