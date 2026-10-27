-- Additive and repeatable. A virtual participation window cannot be shortened by the participant.
CREATE TABLE IF NOT EXISTS contest_virtual_run (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  uid VARCHAR(32) NOT NULL,
  cid BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  title VARCHAR(500) NOT NULL,
  started_at DATETIME(3) NOT NULL,
  ends_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_virtual_request (uid,request_id),
  KEY idx_virtual_active (uid,ends_at),
  KEY idx_virtual_contest (cid,uid,id),
  CONSTRAINT fk_virtual_contest FOREIGN KEY (cid) REFERENCES contest(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
