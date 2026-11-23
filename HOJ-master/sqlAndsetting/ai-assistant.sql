-- Existing installations: additive; repeatable. Never stores plaintext provider keys.
CREATE TABLE IF NOT EXISTS ai_user_setting (
  uid varchar(32) NOT NULL,
  base_url varchar(512) NOT NULL,
  model varchar(120) NOT NULL,
  key_ciphertext varchar(6000) NOT NULL,
  updated_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Public contests have no password registration. This records authorized statement access, not spectators.
CREATE TABLE IF NOT EXISTS ai_contest_entry (
  uid varchar(32) NOT NULL,
  cid bigint unsigned NOT NULL,
  entered_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (uid,cid),
  KEY idx_ai_entry_contest (cid),
  CONSTRAINT fk_ai_entry_contest FOREIGN KEY (cid) REFERENCES contest(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
