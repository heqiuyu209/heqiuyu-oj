-- Apply external-learning.sql and remote-sources.sql first. Public ACM catalogue and durable page staging only.
CREATE TABLE IF NOT EXISTS remote_nowcoder_problem (
 problem_key VARCHAR(120) NOT NULL PRIMARY KEY,
 native_problem_id BIGINT NULL,
 contest_id BIGINT NULL,
 problem_index VARCHAR(16) NULL,
 question_id VARCHAR(16) NULL,
 title VARCHAR(255) NOT NULL,
 source_rating INT NULL,
 content_hash CHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 available TINYINT(1) NOT NULL DEFAULT 1,
 from_catalog TINYINT(1) NOT NULL DEFAULT 0,
 last_seen_at DATETIME(3) NOT NULL,
 changed_at DATETIME(3) NOT NULL,
 KEY idx_nowcoder_source_rating(available,source_rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS remote_nowcoder_scan (
 source_name VARCHAR(32) NOT NULL PRIMARY KEY,
 scan_token VARCHAR(36) NULL,
 next_page INT NOT NULL DEFAULT 1,
 stage VARCHAR(12) NOT NULL DEFAULT 'HEAD',
 target_total INT NULL,
 last_id BIGINT NULL,
 head_hash CHAR(64) NULL,
 last_head_hash CHAR(64) NULL,
 last_full_success_at DATETIME(3) NULL,
 head_added INT NOT NULL DEFAULT 0,
 head_changed INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS remote_nowcoder_catalog_stage (
 scan_token VARCHAR(36) NOT NULL,
 native_problem_id BIGINT NOT NULL,
 title VARCHAR(255) NOT NULL,
 source_rating INT NULL,
 content_hash CHAR(64) NOT NULL,
 PRIMARY KEY(scan_token,native_problem_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO remote_source_job(source_name,next_run_at) VALUES('NOWCODER_PROBLEMS',CURRENT_TIMESTAMP(3));
INSERT IGNORE INTO remote_nowcoder_scan(source_name) VALUES('NOWCODER_PROBLEMS');
