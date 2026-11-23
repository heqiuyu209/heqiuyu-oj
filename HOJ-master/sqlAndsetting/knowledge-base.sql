-- Additive, repeatable. HOJ owns publication and ACL; the Python index cannot publish.
CREATE TABLE IF NOT EXISTS knowledge_document (
  id char(32) NOT NULL,
  title varchar(200) NOT NULL,
  visibility varchar(16) NOT NULL,
  state varchar(16) NOT NULL,
  latest_version int NOT NULL,
  published_version int DEFAULT NULL,
  revision bigint NOT NULL,
  created_by varchar(32) NOT NULL,
  request_id varchar(64) NOT NULL,
  creation_hash char(64) NOT NULL,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_create (created_by,request_id),
  KEY idx_knowledge_publication (state,visibility,published_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS knowledge_version (
  document_id char(32) NOT NULL,
  version int NOT NULL,
  title varchar(200) NOT NULL,
  format varchar(16) NOT NULL,
  content_hash char(64) NOT NULL,
  content mediumtext NOT NULL,
  state varchar(16) NOT NULL,
  chunks int NOT NULL DEFAULT 0,
  created_by varchar(32) NOT NULL,
  created_at datetime(3) NOT NULL,
  indexed_at datetime(3) DEFAULT NULL,
  PRIMARY KEY (document_id,version),
  CONSTRAINT fk_knowledge_version_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS knowledge_command (
  document_id char(32) NOT NULL,
  uid varchar(32) NOT NULL,
  request_id varchar(64) NOT NULL,
  input_hash char(64) NOT NULL,
  version int NOT NULL,
  PRIMARY KEY (document_id,uid,request_id),
  CONSTRAINT fk_knowledge_command_version FOREIGN KEY (document_id,version) REFERENCES knowledge_version(document_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Immutable original source. No public path; current publication/ACL is checked
-- before any download. Logical document deletion retains audit data as before.
CREATE TABLE IF NOT EXISTS knowledge_source (
  document_id char(32) NOT NULL,
  version int NOT NULL,
  source_name varchar(200) NOT NULL,
  source_hash char(64) NOT NULL,
  byte_length int NOT NULL,
  pages int NOT NULL,
  empty_pages varchar(1024) NOT NULL,
  source_data longblob NOT NULL,
  extraction_mode varchar(16) NOT NULL DEFAULT 'NATIVE',
  PRIMARY KEY (document_id,version),
  CONSTRAINT fk_knowledge_source_version FOREIGN KEY (document_id,version)
    REFERENCES knowledge_version(document_id,version) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS knowledge_job (
  id bigint unsigned NOT NULL AUTO_INCREMENT,
  document_id char(32) NOT NULL,
  version int NOT NULL,
  operation varchar(16) NOT NULL,
  state varchar(16) NOT NULL,
  attempts int NOT NULL DEFAULT 0,
  next_run_at datetime(3) NOT NULL,
  lease_token varchar(36) DEFAULT NULL,
  lease_until datetime(3) DEFAULT NULL,
  last_error varchar(64) DEFAULT NULL,
  updated_at datetime(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_job (document_id,version,operation),
  KEY idx_knowledge_job_due (state,next_run_at,lease_until),
  CONSTRAINT fk_knowledge_job_version FOREIGN KEY (document_id,version) REFERENCES knowledge_version(document_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
