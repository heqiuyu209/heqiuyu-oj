-- Public contest context; never insert a second copy into external_submission.
-- Staging rows become visible only by switching published_token after complete verification.
CREATE TABLE IF NOT EXISTS external_nowcoder_context_job (
 account_id BIGINT NOT NULL PRIMARY KEY,
 stage VARCHAR(24) NOT NULL DEFAULT 'LIST',
 scan_token VARCHAR(36) NOT NULL,
 published_token VARCHAR(36) NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 next_page INT NOT NULL DEFAULT 1,
 list_pages INT NULL,
 list_total INT NULL,
 list_head_hash CHAR(64) NULL,
 contest_id BIGINT NULL,
 page_total INT NULL,
 page_count INT NULL,
 page_head_hash CHAR(64) NULL,
 last_submit_ms BIGINT NULL,
 next_run_at DATETIME(3) NOT NULL,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 failures INT NOT NULL DEFAULT 0,
 last_error VARCHAR(200) NULL,
 last_success_at DATETIME(3) NULL,
 KEY idx_nc_context_due(next_run_at,lease_until),
 CONSTRAINT fk_nc_context_job_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_nowcoder_contest (
 account_id BIGINT NOT NULL,
 scan_token VARCHAR(36) NOT NULL,
 contest_id BIGINT NOT NULL,
 title VARCHAR(255) NOT NULL,
 start_ms BIGINT NOT NULL,
 end_ms BIGINT NOT NULL,
 team TINYINT(1) NOT NULL,
 participation VARCHAR(24) NOT NULL,
 restricted TINYINT(1) NOT NULL,
 official_accepted INT NULL,
 contest_rank INT NULL,
 detail_state VARCHAR(24) NOT NULL,
 PRIMARY KEY(account_id,scan_token,contest_id),
 CONSTRAINT fk_nc_contest_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_nowcoder_contest_problem (
 account_id BIGINT NOT NULL,
 scan_token VARCHAR(36) NOT NULL,
 contest_id BIGINT NOT NULL,
 problem_index VARCHAR(16) NOT NULL,
 problem_key VARCHAR(120) NOT NULL,
 title VARCHAR(255) NOT NULL,
 PRIMARY KEY(account_id,scan_token,contest_id,problem_index),
 UNIQUE KEY uk_nc_context_problem(account_id,scan_token,contest_id,problem_key),
 CONSTRAINT fk_nc_context_problem_contest FOREIGN KEY(account_id,scan_token,contest_id)
  REFERENCES external_nowcoder_contest(account_id,scan_token,contest_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_nowcoder_contest_submission (
 account_id BIGINT NOT NULL,
 scan_token VARCHAR(36) NOT NULL,
 contest_id BIGINT NOT NULL,
 submission_id BIGINT NOT NULL,
 problem_key VARCHAR(120) NOT NULL,
 problem_index VARCHAR(16) NOT NULL,
 verdict VARCHAR(32) NOT NULL,
 submitted_ms BIGINT NOT NULL,
 PRIMARY KEY(account_id,scan_token,contest_id,submission_id),
 KEY idx_nc_context_problem(account_id,scan_token,contest_id,problem_key),
 CONSTRAINT fk_nc_context_submission_contest FOREIGN KEY(account_id,scan_token,contest_id)
  REFERENCES external_nowcoder_contest(account_id,scan_token,contest_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
