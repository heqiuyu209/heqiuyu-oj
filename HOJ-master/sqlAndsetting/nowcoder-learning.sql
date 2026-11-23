-- Official public regular-practice evidence; no login cookies or remote submission data.
CREATE TABLE IF NOT EXISTS external_nowcoder_profile (
 account_id BIGINT NOT NULL PRIMARY KEY,
 display_name VARCHAR(255) NULL,
 challenged_count INT NULL,
 solved_count INT NULL,
 submission_count INT NULL,
 scan_challenged_count INT NULL,
 scan_solved_count INT NULL,
 scan_submission_count INT NULL,
 scan_pages INT NULL,
 scan_last_second BIGINT NULL,
 scan_head_hash VARCHAR(64) NULL,
 CONSTRAINT fk_external_nowcoder_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
