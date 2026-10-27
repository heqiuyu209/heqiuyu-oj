-- Own saved, server-verified recommendation sets. Additive and repeatable.
CREATE TABLE IF NOT EXISTS learning_training_plan (
 id CHAR(32) NOT NULL PRIMARY KEY,
 uid VARCHAR(32) NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 name VARCHAR(320) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 snapshot_json MEDIUMTEXT NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_learning_plan_request(uid,request_id),
 KEY idx_learning_plan_owner(uid,state,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
