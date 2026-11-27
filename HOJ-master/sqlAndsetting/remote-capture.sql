CREATE TABLE IF NOT EXISTS remote_capture_policy (
    id TINYINT NOT NULL PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO remote_capture_policy (id, enabled) VALUES (1, 0);

CREATE TABLE IF NOT EXISTS remote_capture_credential (
    uid VARCHAR(32) NOT NULL PRIMARY KEY,
    secret_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    UNIQUE KEY remote_capture_secret (secret_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS remote_capture_queue (
    problem_key VARCHAR(128) NOT NULL PRIMARY KEY,
    display_id VARCHAR(100) NOT NULL,
    source_url VARCHAR(300) NOT NULL,
    uploader VARCHAR(32) NOT NULL,
    state VARCHAR(12) NOT NULL,
    lease_token CHAR(32) NULL,
    lease_until DATETIME(3) NULL,
    title VARCHAR(255) NULL,
    content_json MEDIUMTEXT NULL,
    queued_at DATETIME(3) NULL,
    KEY remote_capture_ready (state, queued_at, problem_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
