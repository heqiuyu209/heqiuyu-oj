-- Independent public paper projections. These rows are not programming submissions or question attempts.
CREATE TABLE IF NOT EXISTS external_nowcoder_practice_job (
 account_id BIGINT NOT NULL PRIMARY KEY,
 stage VARCHAR(24) NOT NULL DEFAULT 'PROFILE',
 scan_token VARCHAR(36) NOT NULL,
 published_token VARCHAR(36) NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 source_visible TINYINT(1) NULL,
 next_page INT NOT NULL DEFAULT 1,
 all_total INT NULL,
 finished_total INT NULL,
 next_run_at DATETIME(3) NOT NULL,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 failures INT NOT NULL DEFAULT 0,
 last_error VARCHAR(200) NULL,
 last_success_at DATETIME(3) NULL,
 KEY idx_nc_practice_due(next_run_at,lease_until),
 CONSTRAINT fk_nc_practice_job_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS external_nowcoder_practice_record (
 account_id BIGINT NOT NULL,
 scan_token VARCHAR(36) NOT NULL,
 projection VARCHAR(8) NOT NULL,
 paper_id BIGINT NOT NULL,
 record_id BIGINT NOT NULL,
 page_no INT NOT NULL,
 position_no INT NOT NULL,
 kind VARCHAR(12) NOT NULL,
 completed TINYINT(1) NOT NULL,
 record_json TEXT NOT NULL,
 record_hash CHAR(64) NOT NULL,
 PRIMARY KEY(account_id,scan_token,projection,paper_id),
 UNIQUE KEY uk_nc_practice_record(account_id,scan_token,projection,record_id),
 KEY idx_nc_practice_page(account_id,scan_token,projection,page_no,position_no),
 CONSTRAINT fk_nc_practice_record_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
