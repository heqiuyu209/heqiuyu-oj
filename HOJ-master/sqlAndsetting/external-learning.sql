-- Public external learning evidence. Additive and repeatable; no passwords or remote-submit credentials.
CREATE TABLE IF NOT EXISTS external_account (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 uid VARCHAR(32) NOT NULL,
 platform VARCHAR(24) NOT NULL,
 handle VARCHAR(64) NOT NULL,
 handle_key VARCHAR(64) NOT NULL,
 verified_identity VARCHAR(64) NULL,
 verified_at DATETIME(3) NULL,
 proof_code VARCHAR(64) NULL,
 proof_expires_at DATETIME(3) NULL,
 verification_note VARCHAR(200) NULL,
 registration_time BIGINT NULL,
 rating INT NULL,
 max_rating INT NULL,
 rated_contests INT NULL,
 profile_synced_at DATETIME(3) NULL,
 history_complete TINYINT(1) NOT NULL DEFAULT 0,
 last_success_at DATETIME(3) NULL,
 last_full_at DATETIME(3) NULL,
 last_requested_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_external_user_handle(uid,platform,handle_key),
 UNIQUE KEY uk_external_verified(platform,verified_identity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_sync_job (
 account_id BIGINT NOT NULL PRIMARY KEY,
 stage VARCHAR(24) NOT NULL DEFAULT 'PROFILE',
 from_index INT NOT NULL DEFAULT 1,
 full_scan TINYINT(1) NOT NULL DEFAULT 1,
 checkpoint_id BIGINT NOT NULL DEFAULT 0,
 working_high_id BIGINT NOT NULL DEFAULT 0,
 last_page_min_id BIGINT NULL,
 cf_head_hash CHAR(64) NULL,
 scan_token VARCHAR(36) NOT NULL,
 next_run_at DATETIME(3) NULL,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 failures INT NOT NULL DEFAULT 0,
 last_error VARCHAR(200) NULL,
 KEY idx_external_job_due(next_run_at,lease_until),
 CONSTRAINT fk_external_job_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_problem (
 problem_key VARCHAR(120) NOT NULL PRIMARY KEY,
 platform VARCHAR(24) NOT NULL,
 title VARCHAR(255) NOT NULL,
 rating INT NULL,
 tags_json TEXT NOT NULL,
 synced_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_submission (
 account_id BIGINT NOT NULL,
 external_id BIGINT NOT NULL,
 problem_key VARCHAR(120) NOT NULL,
 verdict VARCHAR(32) NOT NULL,
 submitted_at BIGINT NOT NULL,
 individual TINYINT(1) NOT NULL,
 seen_scan VARCHAR(36) NOT NULL,
 PRIMARY KEY(account_id,external_id),
 KEY idx_external_evidence(account_id,problem_key,verdict),
 CONSTRAINT fk_external_submission_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_rating_event (
 account_id BIGINT NOT NULL,
 contest_id BIGINT NOT NULL,
 title VARCHAR(255) NOT NULL,
 contest_rank INT NOT NULL,
 old_rating INT NOT NULL,
 new_rating INT NOT NULL,
 occurred_at BIGINT NOT NULL,
 PRIMARY KEY(account_id,contest_id),
 CONSTRAINT fk_external_rating_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
