-- Durable personal submissions. Apply external-learning.sql and remote-credentials.sql first.
-- Credentials and HTTP cookies never belong in this journal. Repeatable and additive.
CREATE TABLE IF NOT EXISTS personal_remote_task (
 submit_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 owner_uid VARCHAR(32) NOT NULL,
 account_id BIGINT NULL,
 account_ref BIGINT NOT NULL,
 platform VARCHAR(20) NOT NULL,
 handle_key VARCHAR(64) NOT NULL,
 credential_version BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 request_hash CHAR(64) NULL,
 remote_problem VARCHAR(120) NOT NULL,
 source_contest VARCHAR(80) NULL,
 language_id VARCHAR(20) NOT NULL,
 code_hash CHAR(64) NOT NULL,
 payload_hash CHAR(64) NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 next_run_at DATETIME(3) NULL,
 baseline_id BIGINT NULL,
 remote_submit_id BIGINT NULL,
 submit_attempts INT NOT NULL DEFAULT 0,
 read_failures INT NOT NULL DEFAULT 0,
 error_code VARCHAR(32) NULL,
 post_started_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_personal_remote_request(owner_uid,request_id),
 KEY idx_personal_remote_due(state,next_run_at,lease_until),
 KEY idx_personal_remote_account(platform,handle_key,state),
 CONSTRAINT fk_personal_remote_judge FOREIGN KEY(submit_id) REFERENCES judge(submit_id) ON DELETE CASCADE,
 CONSTRAINT fk_personal_remote_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The lane survives unlink/relink: uncertain requests must not release the same remote identity.
CREATE TABLE IF NOT EXISTS personal_remote_lane (
 platform VARCHAR(20) NOT NULL,
 handle_key VARCHAR(64) NOT NULL,
 active_submit_id BIGINT UNSIGNED NULL,
 PRIMARY KEY(platform,handle_key),
 CONSTRAINT fk_personal_remote_lane FOREIGN KEY(active_submit_id) REFERENCES personal_remote_task(submit_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
