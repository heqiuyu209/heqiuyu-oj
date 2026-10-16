-- Objective practice schema (canonical incremental migration).
-- Run once against the existing HOJ database. This migration creates tables only.
-- The same schema is included at the end of hoj.sql for clean installations.
CREATE TABLE IF NOT EXISTS objective_question (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    question_type VARCHAR(24) NOT NULL,
    version INT NOT NULL,
    published TINYINT(1) NOT NULL DEFAULT 0,
    practice_enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_by VARCHAR(32) NOT NULL,
    content_json LONGTEXT NOT NULL,
    gmt_create DATETIME(3) NOT NULL,
    gmt_modified DATETIME(3) NOT NULL,
    KEY idx_objective_question_owner (created_by, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS objective_question_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    version INT NOT NULL,
    content_json LONGTEXT NOT NULL,
    gmt_create DATETIME(3) NOT NULL,
    UNIQUE KEY uk_objective_question_version (question_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS objective_set (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    question_ids_json TEXT NOT NULL,
    version INT NOT NULL,
    published TINYINT(1) NOT NULL DEFAULT 0,
    answer_policy VARCHAR(24) NOT NULL,
    explanation_policy VARCHAR(24) NOT NULL,
    answer_release_at BIGINT NULL,
    explanation_release_at BIGINT NULL,
    created_by VARCHAR(32) NOT NULL,
    gmt_create DATETIME(3) NOT NULL,
    gmt_modified DATETIME(3) NOT NULL,
    KEY idx_objective_set_public (published, id),
    KEY idx_objective_set_owner (created_by, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS objective_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    set_id BIGINT NULL,
    set_version INT NULL,
    question_id BIGINT NULL,
    uid VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    title VARCHAR(200) NOT NULL,
    state VARCHAR(16) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    answers_json LONGTEXT NOT NULL,
    gmt_create DATETIME(3) NOT NULL,
    gmt_modified DATETIME(3) NOT NULL,
    UNIQUE KEY uk_objective_run_request (uid, request_id),
    KEY idx_objective_run_user (uid, id),
    KEY idx_objective_run_set (set_id),
    KEY idx_objective_run_question (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
