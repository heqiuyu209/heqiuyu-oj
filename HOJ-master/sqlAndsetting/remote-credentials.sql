-- Personal remote credentials, separate from the legacy administrator account pool.
-- Apply external-learning.sql first; additive and repeatable.
CREATE TABLE IF NOT EXISTS remote_user_credential (
 account_id BIGINT NOT NULL PRIMARY KEY,
 credential_ciphertext TEXT NULL,
 credential_version BIGINT NOT NULL DEFAULT 1,
 updated_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_remote_credential_account FOREIGN KEY(account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
