-- Additive/repeatable. Apply after knowledge-base.sql; raw media stays in a private volume.
CREATE TABLE IF NOT EXISTS knowledge_video_quota (
  owner varchar(32) NOT NULL,
  active_count int NOT NULL DEFAULT 0,
  PRIMARY KEY (owner)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
INSERT INTO knowledge_video_quota(owner,active_count)
SELECT '_all',0 WHERE NOT EXISTS(SELECT 1 FROM knowledge_video_quota WHERE owner='_all');

CREATE TABLE IF NOT EXISTS knowledge_video_import (
  id char(32) NOT NULL,
  requested_by varchar(32) NOT NULL,
  processing_by varchar(32) NOT NULL,
  request_id varchar(64) NOT NULL,
  input_hash char(64) NOT NULL,
  title varchar(200) NOT NULL,
  visibility varchar(16) NOT NULL,
  target_document_id char(32) DEFAULT NULL,
  expected_revision bigint DEFAULT NULL,
  source_name varchar(200) NOT NULL,
  asset_id char(32) NOT NULL,
  source_hash char(64) NOT NULL,
  byte_length bigint NOT NULL,
  language varchar(8) NOT NULL,
  state varchar(16) NOT NULL,
  stage varchar(16) NOT NULL,
  probe_json text DEFAULT NULL,
  duration_ms bigint DEFAULT NULL,
  next_ms bigint NOT NULL DEFAULT 0,
  segments mediumtext NOT NULL,
  model_hash char(64) DEFAULT NULL,
  engine varchar(64) DEFAULT NULL,
  attempts int NOT NULL DEFAULT 0,
  stage_attempts int NOT NULL DEFAULT 0,
  next_run_at datetime(3) NOT NULL,
  lease_token varchar(36) DEFAULT NULL,
  lease_until datetime(3) DEFAULT NULL,
  last_error varchar(64) DEFAULT NULL,
  revision bigint NOT NULL,
  result_document_id char(32) DEFAULT NULL,
  result_version int DEFAULT NULL,
  saved_input_hash char(64) DEFAULT NULL,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_video_upload_nonce(requested_by,request_id),
  UNIQUE KEY uk_video_upload_asset(asset_id),
  KEY idx_video_due(state,next_run_at,lease_until),
  KEY idx_video_owner(requested_by,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS knowledge_video_source (
  document_id char(32) NOT NULL,
  version int NOT NULL,
  asset_id char(32) NOT NULL,
  source_name varchar(200) NOT NULL,
  source_hash char(64) NOT NULL,
  byte_length bigint NOT NULL,
  duration_ms bigint NOT NULL,
  mime varchar(32) NOT NULL,
  language varchar(8) NOT NULL,
  engine varchar(64) NOT NULL,
  model_hash char(64) NOT NULL,
  extraction_mode varchar(16) NOT NULL,
  PRIMARY KEY(document_id,version),
  KEY idx_video_source_asset(asset_id),
  CONSTRAINT fk_video_source_version FOREIGN KEY(document_id,version)
    REFERENCES knowledge_version(document_id,version) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
