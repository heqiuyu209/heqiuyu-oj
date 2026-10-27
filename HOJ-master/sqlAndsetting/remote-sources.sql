-- Public source catalogue, import queue and versioned statements. Additive and repeatable.
CREATE TABLE IF NOT EXISTS remote_source_job (
 source_name VARCHAR(32) NOT NULL PRIMARY KEY,
 next_run_at DATETIME(3) NULL,
 last_requested_at DATETIME(3) NULL,
 last_success_at DATETIME(3) NULL,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 failures INT NOT NULL DEFAULT 0,
 last_error VARCHAR(200) NULL,
 total_count INT NOT NULL DEFAULT 0,
 added_count INT NOT NULL DEFAULT 0,
 changed_count INT NOT NULL DEFAULT 0,
 removed_count INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO remote_source_job(source_name,next_run_at) VALUES('CODEFORCES_PROBLEMS',CURRENT_TIMESTAMP(3));

CREATE TABLE IF NOT EXISTS remote_catalog_problem (
 problem_key VARCHAR(120) NOT NULL PRIMARY KEY,
 contest_id BIGINT NOT NULL,
 problem_index VARCHAR(16) NOT NULL,
 title VARCHAR(255) NOT NULL,
 rating INT NULL,
 tags_json TEXT NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 available TINYINT(1) NOT NULL DEFAULT 1,
 seen_token VARCHAR(36) NOT NULL,
 last_seen_at DATETIME(3) NOT NULL,
 changed_at DATETIME(3) NOT NULL,
 KEY idx_remote_catalog_rating(available,rating),
 KEY idx_remote_catalog_order(contest_id,problem_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS remote_problem_import (
 problem_key VARCHAR(120) NOT NULL PRIMARY KEY,
 requested_by VARCHAR(32) NOT NULL,
 requested_author VARCHAR(100) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(3) NULL,
 last_requested_at DATETIME(3) NOT NULL,
 next_run_at DATETIME(3) NULL,
 failures INT NOT NULL DEFAULT 0,
 last_error VARCHAR(200) NULL,
 problem_id BIGINT NULL,
 imported_hash VARCHAR(64) NULL,
 catalog_hash VARCHAR(64) NULL,
 auto_refresh TINYINT(1) NULL DEFAULT NULL,
 last_checked_at DATETIME(3) NULL,
 last_success_at DATETIME(3) NULL,
 KEY idx_remote_import_due(state,next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS remote_statement_version (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 problem_key VARCHAR(120) NOT NULL,
 language VARCHAR(12) NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 source_hash VARCHAR(64) NOT NULL,
 content_json MEDIUMTEXT NOT NULL,
 origin VARCHAR(16) NOT NULL,
 review_status VARCHAR(16) NOT NULL,
 created_by VARCHAR(32) NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_remote_statement_content(problem_key,language,content_hash,source_hash),
 KEY idx_remote_statement_version(problem_key,language,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS remote_statement_current (
 problem_key VARCHAR(120) NOT NULL PRIMARY KEY,
 version_id BIGINT NOT NULL,
 translation_version_id BIGINT NULL,
 CONSTRAINT fk_remote_statement_current FOREIGN KEY(version_id) REFERENCES remote_statement_version(id),
 CONSTRAINT fk_remote_translation_current FOREIGN KEY(translation_version_id) REFERENCES remote_statement_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
